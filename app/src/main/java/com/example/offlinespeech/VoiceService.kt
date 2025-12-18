package com.example.offlinespeech

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.*

class VoiceService : Service() {

    private lateinit var recognizer: Recognizer
    private lateinit var audioRecord: AudioRecord
    private var running = false

    override fun onCreate() {
        super.onCreate()
        Log.d("VOICE", "VoiceService onCreate()")

        try {
            // Создаем уведомление ДО startForeground
            startForeground(NOTIFICATION_ID, createNotification())
            Log.d("VOICE", "Foreground notification started")

            // Инициализируем распознаватель
            if (initRecognizer()) {
                startListening()
            } else {
                Log.e("VOICE", "Failed to initialize recognizer")
                stopSelf()
            }

        } catch (e: Exception) {
            Log.e("VOICE", "Error starting foreground service", e)

            // Для Android 12+ показываем уведомление
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                showBackgroundRestrictedNotification()
            }
            stopSelf()
        }
    }

    private fun initRecognizer(): Boolean {
        return try {
            Log.d("VOICE", "Инициализация распознавателя...")

            // Проверяем разрешение
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                Log.e("VOICE", "Нет разрешения на запись аудио!")
                return false
            }

            // Проверяем и копируем модель
            val modelDir = File(filesDir, "model")
            if (!modelDir.exists() || modelDir.listFiles()?.isEmpty() != false) {
                Log.d("VOICE", "Модель не найдена, копируем...")
                copyModelFromAssets()
                Thread.sleep(500) // Даем время на копирование
            }

            if (!modelDir.exists() || modelDir.listFiles()?.isEmpty() != false) {
                Log.e("VOICE", "Не удалось скопировать модель!")
                return false
            }

            Log.d("VOICE", "Загружаем модель из: ${modelDir.absolutePath}")
            val model = Model(modelDir.absolutePath)

            // Создаем распознаватель
            recognizer = Recognizer(model, 16000.0f)
            Log.d("VOICE", "Распознаватель создан")

            // Инициализируем AudioRecord
            val bufferSize = AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )

            Log.d("VOICE", "AudioRecord инициализирован")
            true

        } catch (e: Exception) {
            Log.e("VOICE", "Ошибка инициализации распознавателя", e)
            false
        }
    }

    private fun copyModelFromAssets() {
        try {
            Log.d("VOICE", "Копирование модели из assets")

            val modelDir = File(filesDir, "model")

            // Очищаем старую папку
            if (modelDir.exists()) {
                modelDir.deleteRecursively()
            }
            modelDir.mkdirs()

            Log.d("VOICE", "Папка создана: ${modelDir.absolutePath}")

            // Проверяем, что в assets есть папка 'model'
            try {
                val filesInModel = assets.list("model")
                if (filesInModel != null && filesInModel.isNotEmpty()) {
                    Log.d("VOICE", "Найдена папка 'model' в assets с файлами: ${filesInModel.size}")

                    // Копируем всю папку рекурсивно
                    copyAssetsFolder("model", modelDir)

                } else {
                    Log.e("VOICE", "Папка 'model' пуста или не найдена в assets")

                    // Проверяем другие возможные расположения
                    val allAssets = assets.list("")
                    Log.d("VOICE", "Все файлы в assets: ${allAssets?.joinToString(", ")}")

                    // Ищем файлы модели в корне assets
                    allAssets?.forEach { fileName ->
                        if (fileName.contains("am") ||
                            fileName.contains("conf") ||
                            fileName.contains("graph") ||
                            fileName.contains("ivector") ||
                            fileName.endsWith(".model") ||
                            fileName.endsWith(".zip")) {

                            Log.d("VOICE", "Копируем файл модели: $fileName")
                            copySingleFileFromAssets(fileName, File(modelDir, fileName))
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("VOICE", "Ошибка чтения assets", e)
            }

            // Проверяем результат
            val copiedFiles = modelDir.listFiles()
            if (copiedFiles != null && copiedFiles.isNotEmpty()) {
                Log.d("VOICE", "Модель скопирована успешно. Файлы: ${copiedFiles.joinToString { it.name }}")
            } else {
                Log.e("VOICE", "Модель НЕ скопирована! Папка пуста.")
                // Создаем тестовые файлы для отладки
                File(modelDir, "test.txt").writeText("Test file")
            }

        } catch (e: Exception) {
            Log.e("VOICE", "Ошибка копирования модели", e)
        }
    }

    private fun copyAssetsFolder(assetPath: String, targetDir: File) {
        try {
            val files = assets.list(assetPath)
            if (files == null) {
                Log.w("VOICE", "Нет файлов в папке: $assetPath")
                return
            }

            Log.d("VOICE", "Копируем папку '$assetPath' (${files.size} файлов)")

            for (fileName in files) {
                val assetFilePath = "$assetPath/$fileName"
                val targetFile = File(targetDir, fileName)

                try {
                    // Пытаемся открыть как файл
                    assets.open(assetFilePath).use {
                        // Если открылось, значит это файл
                        copySingleFileFromAssets(assetFilePath, targetFile)
                    }
                } catch (e: FileNotFoundException) {
                    // Если не открывается как файл, возможно это папка
                    Log.d("VOICE", "$assetFilePath - возможно папка, создаем подпапку")
                    targetFile.mkdirs()
                    copyAssetsFolder(assetFilePath, targetFile)
                } catch (e: Exception) {
                    Log.e("VOICE", "Ошибка обработки: $assetFilePath", e)
                }
            }

        } catch (e: Exception) {
            Log.e("VOICE", "Ошибка копирования папки: $assetPath", e)
        }
    }

    private fun copySingleFileFromAssets(assetPath: String, targetFile: File) {
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            inputStream = assets.open(assetPath)
            outputStream = FileOutputStream(targetFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytes = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytes += bytesRead
            }

            outputStream.flush()
            Log.d("VOICE", "Скопирован файл: $assetPath -> ${targetFile.name} ($totalBytes байт)")

        } catch (e: FileNotFoundException) {
            Log.e("VOICE", "Файл не найден в assets: $assetPath", e)
        } catch (e: IOException) {
            Log.e("VOICE", "Ошибка при копировании файла: $assetPath", e)
        } finally {
            try {
                inputStream?.close()
                outputStream?.close()
            } catch (e: IOException) {
                Log.e("VOICE", "Ошибка при закрытии потоков", e)
            }
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e("VOICE", "Нет разрешения на запись аудио!")
            stopSelf()
            return
        }

        try {
            audioRecord.startRecording()
            Log.d("VOICE", "Запись аудио начата")

            running = true

            Thread {
                val buffer = ShortArray(4096)

                while (running) {
                    try {
                        val read = audioRecord.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            processAudio(buffer, read)
                        }
                    } catch (e: Exception) {
                        Log.e("VOICE", "Ошибка чтения аудио", e)
                        break
                    }
                }

                Log.d("VOICE", "Аудио поток завершен")
            }.start()

        } catch (e: Exception) {
            Log.e("VOICE", "Ошибка запуска записи", e)
            stopSelf()
        }
    }

    private fun processAudio(buffer: ShortArray, length: Int) {
        try {
            val resultJson = if (recognizer.acceptWaveForm(buffer, length)) {
                recognizer.result.toString()
            } else {
                recognizer.partialResult.toString()
            }

            // Парсим результат
            val json = JSONObject(resultJson)
            val text = json.optString("text", "")

            if (text.isNotEmpty()) {
                Log.d("VOICE", "Распознано: $text")
                handleRecognizedText(text)
            }

        } catch (e: Exception) {
            Log.e("VOICE", "Ошибка обработки аудио", e)
        }
    }

    private fun handleRecognizedText(text: String) {
        // Обрабатываем распознанный текст
        if (text.contains("старт", ignoreCase = true)) {
            Log.d("VOICE", "Команда СТАРТ распознана!")
            // Здесь можно выполнить действие
        }

        if (text.contains("стоп", ignoreCase = true)) {
            Log.d("VOICE", "Команда СТОП распознана!")
            stopSelf()
        }
    }

    private fun createNotification(): Notification {
        createNotificationChannel()

        val intent = Intent(this, com.example.voicehelperforym.MainActivity::class.java)
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        } else {
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Голосовой помощник")
            .setContentText("Слушаю команды...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Recognition",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Фоновое распознавание голосовых команд"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun showBackgroundRestrictedNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "background_restricted_channel"
            val channel = NotificationChannel(
                channelId,
                "Background Restrictions",
                NotificationManager.IMPORTANCE_HIGH
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)

            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("Требуется действие")
                .setContentText("Нажмите, чтобы запустить голосового помощника")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this, 0,
                        Intent(this, com.example.voicehelperforym.MainActivity::class.java),
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                        else
                            PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                .setAutoCancel(true)
                .build()

            manager.notify(999, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("VOICE", "VoiceService onStartCommand()")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            START_NOT_STICKY
        } else {
            START_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d("VOICE", "VoiceService onDestroy()")

        running = false

        try {
            if (::audioRecord.isInitialized) {
                audioRecord.stop()
                audioRecord.release()
                Log.d("VOICE", "AudioRecord освобожден")
            }
        } catch (e: Exception) {
            Log.e("VOICE", "Ошибка остановки AudioRecord", e)
        }

        try {
            if (::recognizer.isInitialized) {
                recognizer.close()
                Log.d("VOICE", "Recognizer закрыт")
            }
        } catch (e: Exception) {
            Log.e("VOICE", "Ошибка закрытия recognizer", e)
        }
    }

    companion object {
        private const val CHANNEL_ID = "voice_recognition_channel"
        private const val NOTIFICATION_ID = 1
    }
}