package com.example.offlinespeech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
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

    // Аудио-эффекты и настраиваемые параметры
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var automaticGainControl: AutomaticGainControl? = null

    // Уровень "дальности" микрофона (0–10, по умолчанию 5 = средний)
    private var micRangeLevel: Int = 5

    // Режим источника аудио: VOICE_RECOGNITION (0) или VOICE_COMMUNICATION (1)
    private var audioSourceMode: Int = AUDIO_SOURCE_MODE_VOICE_RECOGNITION

    // Минимальная длина partial-результата
    private var minPartialLength: Int = 3

    // Включено ли шумоподавление и сопутствующие эффекты (NS, AEC, AGC)
    private var noiseSuppressionEnabled: Boolean = true

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
            val bufferSize = AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            // Выбор источника аудио на основе настроек
            val audioSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                when (audioSourceMode) {
                    AUDIO_SOURCE_MODE_VOICE_COMMUNICATION -> {
                        Log.d("VoskRecognizer", "Используем источник VOICE_COMMUNICATION")
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    }
                    else -> {
                        Log.d("VoskRecognizer", "Используем источник VOICE_RECOGNITION")
                        MediaRecorder.AudioSource.VOICE_RECOGNITION
                    }
                }
            } else {
                Log.d("VoskRecognizer", "Используем источник MIC (старая версия Android)")
                MediaRecorder.AudioSource.MIC
            }

            audioRecord = AudioRecord(
                audioSource,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            // Включаем системное шумоподавление / AEC / AGC при необходимости
            audioRecord?.let { ar ->
                val sessionId = ar.audioSessionId

                if (noiseSuppressionEnabled) {
                    if (NoiseSuppressor.isAvailable()) {
                        try {
                            noiseSuppressor = NoiseSuppressor.create(sessionId)
                            Log.d("VoskRecognizer", "NoiseSuppressor включен")
                        } catch (e: Exception) {
                            Log.e("VoskRecognizer", "Не удалось включить NoiseSuppressor", e)
                        }
                    } else {
                        Log.d("VoskRecognizer", "NoiseSuppressor недоступен на устройстве")
                    }

                    if (AcousticEchoCanceler.isAvailable()) {
                        try {
                            echoCanceler = AcousticEchoCanceler.create(sessionId)
                            Log.d("VoskRecognizer", "AcousticEchoCanceler включен")
                        } catch (e: Exception) {
                            Log.e("VoskRecognizer", "Не удалось включить AcousticEchoCanceler", e)
                        }
                    } else {
                        Log.d("VoskRecognizer", "AcousticEchoCanceler недоступен на устройстве")
                    }

                    // AGC особенно полезен для \"дальней\" речи, включаем его при micRangeLevel > 0
                    if (micRangeLevel > 0 && AutomaticGainControl.isAvailable()) {
                        try {
                            automaticGainControl = AutomaticGainControl.create(sessionId)
                            Log.d("VoskRecognizer", "AutomaticGainControl включен (micRangeLevel=$micRangeLevel)")
                        } catch (e: Exception) {
                            Log.e("VoskRecognizer", "Не удалось включить AutomaticGainControl", e)
                        }
                    } else {
                        Log.d(
                            "VoskRecognizer",
                            "AutomaticGainControl не включен (micRangeLevel=$micRangeLevel, isAvailable=${AutomaticGainControl.isAvailable()})"
                        )
                    }
                } else {
                    Log.d("VoskRecognizer", "Шумоподавление отключено настройками, эффекты не создаются")
                }
            }

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
                                // Применяем программное усиление в зависимости от \"дальности\"
                                applyMicRangeGain(buffer, read)

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

            // Освобождаем аудио-эффекты
            try {
                noiseSuppressor?.release()
            } catch (e: Exception) {
                Log.e("VoskRecognizer", "Ошибка освобождения NoiseSuppressor", e)
            }
            noiseSuppressor = null

            try {
                echoCanceler?.release()
            } catch (e: Exception) {
                Log.e("VoskRecognizer", "Ошибка освобождения AcousticEchoCanceler", e)
            }
            echoCanceler = null

            try {
                automaticGainControl?.release()
            } catch (e: Exception) {
                Log.e("VoskRecognizer", "Ошибка освобождения AutomaticGainControl", e)
            }
            automaticGainControl = null

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
                // Порог длины настраивается через minPartialLength
                if (partialText.length >= minPartialLength.coerceAtLeast(1)) {
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

    /**
     * Установить уровень \"дальности\" микрофона (0–10).
     * Чем выше значение, тем сильнее программное усиление тихих сигналов.
     */
    fun setMicRange(level: Int) {
        micRangeLevel = level.coerceIn(0, 10)
        Log.d("VoskRecognizer", "Установлен micRangeLevel=$micRangeLevel")
    }

    /**
     * Включить или отключить шумоподавление (NoiseSuppressor, AEC, AGC).
     * Настройка применяется при следующем запуске startListening().
     */
    fun setNoiseSuppressionEnabled(enabled: Boolean) {
        noiseSuppressionEnabled = enabled
        Log.d("VoskRecognizer", "Шумоподавление (NS/AEC/AGC) включено: $noiseSuppressionEnabled")
    }

    /**
     * Установить режим источника аудио:
     * AUDIO_SOURCE_MODE_VOICE_RECOGNITION или AUDIO_SOURCE_MODE_VOICE_COMMUNICATION.
     */
    fun setAudioSourceMode(mode: Int) {
        audioSourceMode = when (mode) {
            AUDIO_SOURCE_MODE_VOICE_COMMUNICATION -> AUDIO_SOURCE_MODE_VOICE_COMMUNICATION
            else -> AUDIO_SOURCE_MODE_VOICE_RECOGNITION
        }
        val modeName = if (audioSourceMode == AUDIO_SOURCE_MODE_VOICE_COMMUNICATION) {
            "VOICE_COMMUNICATION"
        } else {
            "VOICE_RECOGNITION"
        }
        Log.d("VoskRecognizer", "Установлен режим источника аудио: $modeName")
    }

    /**
     * Установить минимальную длину partial-результата (в символах).
     * Значение меньше 1 автоматически повышается до 1.
     */
    fun setMinPartialLength(length: Int) {
        minPartialLength = length.coerceAtLeast(1)
        Log.d("VoskRecognizer", "Установлен minPartialLength=$minPartialLength")
    }

    /**
     * Простое программное усиление сигнала в зависимости от micRangeLevel.
     * Выполняется аккуратно, с защитой от переполнения short.
     */
    private fun applyMicRangeGain(buffer: ShortArray, length: Int) {
        // Нормализуем уровень: -5..+5
        val normalized = micRangeLevel.coerceIn(0, 10) - 5
        if (normalized <= 0) {
            // При низких уровнях \"дальности\" не усиливаем дополнительно
            return
        }

        // Чем выше normalized, тем больше усиление; максимум ~ x1.75
        val factor = 1.0f + normalized * 0.15f

        for (i in 0 until length) {
            val v = (buffer[i] * factor).toInt()
            val clipped = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[i] = clipped.toShort()
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

    companion object {
        const val AUDIO_SOURCE_MODE_VOICE_RECOGNITION = 0
        const val AUDIO_SOURCE_MODE_VOICE_COMMUNICATION = 1
    }
}