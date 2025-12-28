package com.example.offlinespeech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.voicehelperforym.MainActivity

class VoiceService : Service(), VoskRecognizer.RecognitionListener {

    private lateinit var voskRecognizer: VoskRecognizer
    private lateinit var mediaController: MediaController

    override fun onCreate() {
        super.onCreate()
        Log.d("VOICE", "VoiceService onCreate()")

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("VOICE", "Нет разрешения на запись аудио! Сервис останавливается.")
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, createNotification("Инициализация..."))

        mediaController = MediaController(this)
        voskRecognizer = VoskRecognizer(this, this)
        voskRecognizer.initialize()
    }

    override fun onStatusChange(status: String) {
        Log.d("VOICE_SERVICE", "Vosk status: $status")
        when (status) {
            "Готов к работе" -> {
                // Начинаем постоянное распознавание
                voskRecognizer.startListening()
            }
            "Слушаю..." -> {
                updateNotification("Слушаю команды...")
            }
            "Остановлено" -> {
                updateNotification("Остановлено")
                // Если распознавание остановилось по ошибке, не перезапускаем автоматически
                // Это позволит пользователю остановить сервис вручную
            }
        }
    }

    override fun onResult(result: String) {
        Log.d("VOICE_SERVICE", "Распознано: $result")
        if (result.isNotEmpty()) {
            handleRecognizedText(result)
        }
        // Распознавание продолжается автоматически после обработки результата
    }

    override fun onPartialResult(partialResult: String) {
        // В фоновом режиме частичные результаты обычно не важны, но можем логировать
        Log.d("VOICE_SERVICE", "Частичный результат: $partialResult")
    }

    override fun onError(error: String) {
        Log.e("VOICE_SERVICE", "Ошибка Vosk: $error")
        updateNotification("Ошибка: $error")
        
        // При критических ошибках пытаемся перезапустить распознавание
        if (error.contains("критическая", ignoreCase = true) || 
            error.contains("чтения аудио", ignoreCase = true)) {
            Thread {
                Thread.sleep(2000) // Ждем 2 секунды перед перезапуском
                if (::voskRecognizer.isInitialized) {
                    Log.d("VOICE_SERVICE", "Попытка перезапуска распознавания")
                    try {
                        voskRecognizer.stopListening()
                        Thread.sleep(500)
                        voskRecognizer.startListening()
                    } catch (e: Exception) {
                        Log.e("VOICE_SERVICE", "Ошибка при перезапуске распознавания", e)
                    }
                }
            }.start()
        }
    }

    /**
     * Обработка распознанного текста и поиск триггерных команд
     * Порядок важен: более специфичные команды проверяются первыми
     */
    private fun handleRecognizedText(text: String) {
        val lowerText = text.lowercase().trim()
        
        // Игнорируем пустые результаты
        if (lowerText.isEmpty()) {
            return
        }
        
        // Черный список известных ложных срабатываний (очень короткие слова)
        val ignoreList = listOf("мою", "моя", "мой", "мое", "моего", "моей", "моим", "моих", "моём")
        if (lowerText in ignoreList) {
            Log.d("VOICE_SERVICE", "Игнорируем ложное срабатывание из черного списка: '$lowerText'")
            return
        }
        
        // Игнорируем очень короткие тексты (меньше 3 символов), кроме известных команд
        // Это фильтрует шум, но пропускает короткие команды
        if (lowerText.length < 3) {
            Log.d("VOICE_SERVICE", "Игнорируем слишком короткий текст: '$lowerText'")
            return
        }
        
        Log.d("VOICE_SERVICE", "Проверка текста на команды: '$lowerText'")
        
        // Синонимы триггерного слова (распределены так, чтобы не пересекаться)
        val triggerWords = listOf("помощник", "ассистент", "ассист", "хелпер", "вокс", "бокс", "help", "алиса")
        
        // Команда для остановки сервиса (проверяем первым)
        if (matchesCommandWithTrigger(lowerText, triggerWords, listOf("останови", "выключи", "стоп", "заверши работу"))) {
            Log.d("VOICE", "✓ Команда ОСТАНОВИТЬ СЕРВИС распознана!")
            updateNotification("Остановка сервиса...")
            stopSelf()
            return
        }
        
        // Команды управления громкостью
        
        // Увеличить громкость
        if (matchesCommandWithTrigger(lowerText, triggerWords, listOf("громче", "увеличь громкость", "прибавь громкость", "louder", "volume up"))) {
            Log.d("VOICE", "✓ Команда УВЕЛИЧИТЬ ГРОМКОСТЬ распознана!")
            mediaController.increaseVolume()
            updateNotification("Громкость увеличена")
            return
        }
        
        // Уменьшить громкость
        if (matchesCommandWithTrigger(lowerText, triggerWords, listOf("тише", "уменьши громкость", "убавь громкость", "quieter", "volume down"))) {
            Log.d("VOICE", "✓ Команда УМЕНЬШИТЬ ГРОМКОСТЬ распознана!")
            mediaController.decreaseVolume()
            updateNotification("Громкость уменьшена")
            return
        }
        
        // Громкость максимум
        if (matchesCommandWithTrigger(lowerText, triggerWords, listOf("максимум", "максимальная громкость", "максимум громкость", "громкость максимум", "maximum volume", "max volume"))) {
            Log.d("VOICE", "✓ Команда ГРОМКОСТЬ МАКСИМУМ распознана!")
            mediaController.setMaxVolume()
            updateNotification("Громкость максимум")
            return
        }
        
        // Громкость минимум (10%)
        if (matchesCommandWithTrigger(lowerText, triggerWords, listOf("минимум", "минимальная громкость", "минимум громкость", "громкость минимум", "minimum volume", "min volume"))) {
            Log.d("VOICE", "✓ Команда ГРОМКОСТЬ МИНИМУМ распознана!")
            mediaController.setMinVolume()
            updateNotification("Громкость минимум (10%)")
            return
        }
        
        // Команды управления музыкой
        
        // Включить/переключить музыку (проверяем более длинные фразы первыми)
        if (matchesCommandWithTrigger(lowerText, triggerWords, listOf("включи музыку", "запусти музыку", "включи песню", "запусти песню", "play music"))) {
            Log.d("VOICE", "✓ Команда PLAY (длинная) распознана!")
            mediaController.togglePlayPause()
            updateNotification("Воспроизведение переключено")
            return
        }
        
        // Короткие команды play
        if (matchesCommandWithTrigger(lowerText, triggerWords, listOf("играй", "плей", "play", "начать", "продолжи", "старт"))) {
            Log.d("VOICE", "✓ Команда PLAY распознана!")
            mediaController.togglePlayPause()
            updateNotification("Воспроизведение переключено")
            return
        }
        
        // Остановить/пауза музыки
        if (matchesCommandWithTrigger(lowerText, triggerWords, listOf("пауза", "стоп", "stop", "pause", "останови музыку", "останови"))) {
            Log.d("VOICE", "✓ Команда PAUSE/STOP распознана!")
            mediaController.togglePlayPause()
            updateNotification("Воспроизведение остановлено")
            return
        }
        
        // Следующий трек (проверяем более длинные фразы первыми)
        if (matchesCommandWithTrigger(lowerText, triggerWords, listOf("следующий трек", "дальше трек", "next track"))) {
            Log.d("VOICE", "✓ Команда NEXT (длинная) распознана!")
            mediaController.nextTrack()
            updateNotification("Следующий трек")
            return
        }
        
        if (matchesCommandWithTrigger(lowerText, triggerWords, listOf("следующий", "дальше", "next", "далее", "некст"))) {
            Log.d("VOICE", "✓ Команда NEXT распознана!")
            mediaController.nextTrack()
            updateNotification("Следующий трек")
            return
        }
        
        // Предыдущий трек (проверяем более длинные фразы первыми)
        if (matchesCommandWithTrigger(lowerText, triggerWords, listOf("предыдущий трек", "назад трек", "previous track"))) {
            Log.d("VOICE", "✓ Команда PREVIOUS (длинная) распознана!")
            mediaController.previousTrack()
            updateNotification("Предыдущий трек")
            return
        }
        
        if (matchesCommandWithTrigger(lowerText, triggerWords, listOf("предыдущий", "назад", "previous", "вернись"))) {
            Log.d("VOICE", "✓ Команда PREVIOUS распознана!")
            mediaController.previousTrack()
            updateNotification("Предыдущий трек")
            return
        }
        
        // Если ни одна команда не найдена, логируем для отладки
        Log.d("VOICE_SERVICE", "Текст не распознан как команда: '$lowerText'")
    }
    
    /**
     * Проверка команды с триггерным словом
     * Формат: [триггерное слово] [команда]
     * Например: "помощник громче", "ассистент пауза"
     */
    private fun matchesCommandWithTrigger(text: String, triggerWords: List<String>, commands: List<String>): Boolean {
        // Проверяем команды с триггерным словом
        for (trigger in triggerWords) {
            for (command in commands) {
                // Различные варианты: "помощник громче", "громче помощник", "помощник, громче"
                val patterns = listOf(
                    "$trigger $command",
                    "$command $trigger",
                    "$trigger, $command",
                    "$command, $trigger"
                )
                
                for (pattern in patterns) {
                    if (text.contains(pattern, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        
        return false
    }
    
    /**
     * Проверка наличия триггерного слова в тексте (устаревший метод)
     * Для коротких команд (<= 6 символов) используем точное совпадение слов
     * Для длинных команд используем поиск подстроки
     */
    private fun matchesTrigger(text: String, triggers: List<String>): Boolean {
        return triggers.any { trigger ->
            val lowerTrigger = trigger.lowercase().trim()
            if (lowerTrigger.length <= 6) {
                // Для коротких команд проверяем точное совпадение слова
                // Это предотвращает ложные срабатывания
                val words = text.split(Regex("\\s+")).map { it.trim() }
                words.any { word -> word.equals(lowerTrigger, ignoreCase = true) } ||
                text.equals(lowerTrigger, ignoreCase = true)
            } else {
                // Для длинных команд используем поиск подстроки
                text.contains(lowerTrigger, ignoreCase = true)
            }
        }
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(contentText: String): Notification {
        createNotificationChannel()

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Голосовой помощник")
            .setContentText(contentText)
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
                CHANNEL_ID, "Voice Recognition", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Фоновое распознавание голосовых команд"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        voskRecognizer.destroy()
        Log.d("VOICE", "VoiceService onDestroy()")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "voice_recognition_channel"
        private const val NOTIFICATION_ID = 1
    }
}