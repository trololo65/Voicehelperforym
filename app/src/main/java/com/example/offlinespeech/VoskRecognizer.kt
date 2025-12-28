package com.example.offlinespeech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

class VoskRecognizer(
    private val context: Context,
    private val listener: RecognitionListener
) {

    interface RecognitionListener {
        fun onStatusChange(status: String)
        fun onResult(result: String)
        fun onPartialResult(partialResult: String)
        fun onError(error: String)
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var recognitionThread: Thread? = null
    private var isRecognizing = false

    fun initialize() {
        Thread {
            try {
                val modelDir = File(context.filesDir, "model")
                if (!modelDir.exists() || modelDir.listFiles()?.isEmpty() != false) {
                    listener.onStatusChange("Подготовка модели...")
                    val modelCopier = ModelCopier(context)
                    modelCopier.copyModelFromAssets()
                }

                model = Model(modelDir.absolutePath)
                listener.onStatusChange("Готов к работе")
            } catch (e: Exception) {
                Log.e("VoskRecognizer", "Ошибка инициализации Vosk", e)
                listener.onError("Ошибка инициализации: ${e.message}")
            }
        }.start()
    }

    fun startListening() {
        if (isRecognizing) return
        if (model == null) {
            listener.onError("Модель не инициализирована")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onError("Нет разрешения на запись аудио")
            return
        }

        isRecognizing = true
        listener.onStatusChange("Слушаю...")

        try {
            recognizer = Recognizer(model, 16000.0f)
            val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            
            // Используем VOICE_RECOGNITION вместо MIC для лучшей фильтрации фонового звука
            // VOICE_RECOGNITION специально оптимизирован для распознавания речи
            val audioSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            } else {
                MediaRecorder.AudioSource.MIC
            }
            
            audioRecord = AudioRecord(audioSource, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            audioRecord?.startRecording()

            recognitionThread = Thread {
                val buffer = ShortArray(bufferSize)
                var errorCount = 0
                while (isRecognizing) {
                    try {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (read > 0) {
                            errorCount = 0 // Сбрасываем счетчик ошибок при успешном чтении
                            try {
                                if (recognizer?.acceptWaveForm(buffer, read) == true) {
                                    val result = recognizer?.result
                                    result?.let { handleJsonResult(it, isPartial = false) }
                                } else {
                                    val partialResult = recognizer?.partialResult
                                    partialResult?.let { handleJsonResult(it, isPartial = true) }
                                }
                            } catch (e: Exception) {
                                Log.e("VoskRecognizer", "Ошибка обработки аудио", e)
                                errorCount++
                                if (errorCount >= 20) {
                                    Log.e("VoskRecognizer", "Слишком много ошибок обработки")
                                    listener.onError("Ошибка обработки аудио")
                                    break
                                }
                            }
                        } else if (read < 0) {
                            // Отрицательное значение означает ошибку чтения
                            Log.w("VoskRecognizer", "Ошибка чтения аудио: $read")
                            errorCount++
                            if (errorCount >= 20) {
                                Log.e("VoskRecognizer", "Слишком много ошибок чтения")
                                listener.onError("Ошибка чтения аудио")
                                break
                            }
                            // Небольшая пауза при ошибке чтения
                            Thread.sleep(100)
                        }
                    } catch (e: Exception) {
                        Log.e("VoskRecognizer", "Критическая ошибка в цикле распознавания", e)
                        errorCount++
                        if (errorCount >= 20) {
                            listener.onError("Критическая ошибка распознавания: ${e.message}")
                            break
                        }
                        Thread.sleep(100)
                    }
                }
                Log.d("VoskRecognizer", "Поток распознавания завершен")
            }
            recognitionThread?.start()
        } catch (e: Exception) {
            Log.e("VoskRecognizer", "Ошибка запуска распознавания", e)
            stopListening()
            listener.onError("Ошибка запуска: ${e.message}")
        }
    }

    fun stopListening() {
        if (!isRecognizing) return

        isRecognizing = false
        listener.onStatusChange("Остановлено")

        recognitionThread?.interrupt()
        recognitionThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            recognizer?.close()
            recognizer = null
        } catch (e: Exception) {
            Log.e("VoskRecognizer", "Ошибка остановки распознавания", e)
            listener.onError("Ошибка остановки: ${e.message}")
        }
    }

    private fun handleJsonResult(jsonString: String, isPartial: Boolean) {
        try {
            val json = JSONObject(jsonString)
            if (isPartial) {
                val partialText = json.optString("partial", "").trim()
                // Для частичных результатов применяем более строгую фильтрацию
                // Отправляем только если текст имеет минимальную длину (3+ символа)
                // Это уменьшает количество ложных срабатываний
                if (partialText.length >= 3) {
                    Log.d("VoskRecognizer", "Partial result: $partialText")
                    listener.onPartialResult(partialText)
                }
            } else {
                // Для финального результата отправляем всегда (даже пустой)
                // Пустой результат означает, что речь не распознана - это нормально
                val text = json.optString("text", "").trim()
                Log.d("VoskRecognizer", "Final result: '$text'")
                listener.onResult(text)
            }
        } catch (e: Exception) {
            Log.e("VoskRecognizer", "Ошибка парсинга JSON", e)
        }
    }

    fun destroy() {
        stopListening()
        // Модель закрывается автоматически при сборке мусора, но для надежности можно и так:
        try {
             model?.close()
        } catch (e: Exception) {
             Log.e("VoskRecognizer", "Ошибка закрытия модели", e)
        }
        model = null
    }
}