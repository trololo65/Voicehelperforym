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
import com.example.offlinespeech.VoiceSettings

class VoiceService : Service(), VoskRecognizer.RecognitionListener {

    private lateinit var voskRecognizer: VoskRecognizer
    private lateinit var mediaController: MediaController
    
    // Для отслеживания речи и временного уменьшения громкости
    private var speechDetected = false
    // Флаг, указывающий, что триггерное слово было активировано (громкость уменьшена)
    // После активации проверяем команды БЕЗ триггерного слова
    private var triggerWordActivated = false
    private val restoreVolumeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var restoreVolumeRunnable: Runnable? = null

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

        // Применяем текущие настройки микрофона к распознавателю
        applyMicSettingsToRecognizer()

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
        
        // Восстанавливаем громкость после завершения распознавания
        restoreVolumeAfterSpeech()
        
        if (result.isNotEmpty()) {
            handleRecognizedText(result)
        }
        // Распознавание продолжается автоматически после обработки результата
    }

    override fun onPartialResult(partialResult: String) {
        val lowerPartial = partialResult.lowercase().trim()
        
        // Уменьшаем громкость только если обнаружено триггерное слово
        // и настройка требует триггерное слово
        if (VoiceSettings.isTriggerWordRequired(this)) {
            val triggerWords = listOf("помощник", "ассистент", "ассист", "хелпер", "вокс", "бокс", "help", "алиса")
            val containsTrigger = triggerWords.any { trigger ->
                lowerPartial.contains(trigger, ignoreCase = true)
            }
            
            // Уменьшаем громкость только если обнаружено триггерное слово и достаточно длинный текст
            // Ставим флаг triggerWordActivated = true, чтобы в дальнейшем проверять команды БЕЗ триггерного слова
            if (containsTrigger && lowerPartial.length >= 8 && !speechDetected) {
                speechDetected = true
                triggerWordActivated = true
                Log.d("VOICE_SERVICE", "Обнаружено триггерное слово, временно уменьшаем громкость. Флаг активации установлен в true")
                mediaController.temporarilyLowerVolume()
                
                // Отменяем предыдущее восстановление громкости, если оно было запланировано
                restoreVolumeRunnable?.let { restoreVolumeHandler.removeCallbacks(it) }
            }
        }
        
        Log.d("VOICE_SERVICE", "Частичный результат: $partialResult")
    }
    
    /**
     * Восстановить громкость после завершения речи
     * Используем небольшую задержку, чтобы не восстанавливать слишком быстро
     */
    private fun restoreVolumeAfterSpeech() {
        if (!speechDetected) {
            return
        }
        
        // Отменяем предыдущее восстановление, если оно было запланировано
        restoreVolumeRunnable?.let { restoreVolumeHandler.removeCallbacks(it) }
        
        // Планируем восстановление громкости через 1 секунду
        restoreVolumeRunnable = Runnable {
            mediaController.restoreVolume()
            speechDetected = false
            triggerWordActivated = false // Сбрасываем флаг активации триггерного слова
            Log.d("VOICE_SERVICE", "Громкость восстановлена после распознавания. Флаг активации сброшен")
        }
        restoreVolumeHandler.postDelayed(restoreVolumeRunnable!!, 1000)
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
        
        Log.d("VOICE_SERVICE", "Проверка текста на команды: '$lowerText' (triggerWordActivated=$triggerWordActivated)")
        
        // Синонимы триггерного слова (распределены так, чтобы не пересекаться)
        val triggerWords = listOf("помощник", "ассистент", "ассист", "хелпер", "вокс", "бокс", "help", "алиса")
        val requireTrigger = VoiceSettings.isTriggerWordRequired(this)
        
        // Если триггерное слово уже было активировано (громкость уменьшена),
        // проверяем команды БЕЗ триггерного слова (просто ищем команду в тексте)
        // Иначе используем стандартную логику (с триггером или без, в зависимости от настроек)
        val shouldCheckWithoutTrigger = triggerWordActivated
        
        // Команда для остановки сервиса (проверяем первым)
        if (matchesCommand(lowerText, triggerWords, listOf("останови", "выключи", "стоп", "заверши работу"), requireTrigger, shouldCheckWithoutTrigger)) {
            Log.d("VOICE", "✓ Команда ОСТАНОВИТЬ СЕРВИС распознана!")
            updateNotification("Остановка сервиса...")
            stopSelf()
            return
        }
        
        // Команды управления громкостью
        
        // Увеличить громкость
        if (matchesCommand(lowerText, triggerWords, listOf("громче", "увеличь громкость", "прибавь громкость", "louder", "volume up"), requireTrigger, shouldCheckWithoutTrigger)) {
            Log.d("VOICE", "✓ Команда УВЕЛИЧИТЬ ГРОМКОСТЬ распознана!")
            mediaController.increaseVolume()
            updateNotification("Громкость увеличена")
            return
        }
        
        // Уменьшить громкость
        if (matchesCommand(lowerText, triggerWords, listOf("тише", "уменьши громкость", "убавь громкость", "quieter", "volume down"), requireTrigger, shouldCheckWithoutTrigger)) {
            Log.d("VOICE", "✓ Команда УМЕНЬШИТЬ ГРОМКОСТЬ распознана!")
            mediaController.decreaseVolume()
            updateNotification("Громкость уменьшена")
            return
        }
        
        // Громкость максимум
        if (matchesCommand(lowerText, triggerWords, listOf("максимум", "максимальная громкость", "максимум громкость", "громкость максимум", "maximum volume", "max volume"), requireTrigger, shouldCheckWithoutTrigger)) {
            Log.d("VOICE", "✓ Команда ГРОМКОСТЬ МАКСИМУМ распознана!")
            mediaController.setMaxVolume()
            updateNotification("Громкость максимум")
            return
        }
        
        // Громкость минимум (10%)
        if (matchesCommand(lowerText, triggerWords, listOf("минимум", "минимальная громкость", "минимум громкость", "громкость минимум", "minimum volume", "min volume"), requireTrigger, shouldCheckWithoutTrigger)) {
            Log.d("VOICE", "✓ Команда ГРОМКОСТЬ МИНИМУМ распознана!")
            mediaController.setMinVolume()
            updateNotification("Громкость минимум (10%)")
            return
        }
        
        // Команды управления музыкой
        
        // Включить/переключить музыку (проверяем более длинные фразы первыми)
        if (matchesCommand(lowerText, triggerWords, listOf("включи музыку", "запусти музыку", "включи песню", "запусти песню", "play music"), requireTrigger, shouldCheckWithoutTrigger)) {
            Log.d("VOICE", "✓ Команда PLAY (длинная) распознана!")
            mediaController.togglePlayPause()
            updateNotification("Воспроизведение переключено")
            return
        }
        
        // Короткие команды play
        if (matchesCommand(lowerText, triggerWords, listOf("играй", "плей", "play", "начать", "продолжи", "старт"), requireTrigger, shouldCheckWithoutTrigger)) {
            Log.d("VOICE", "✓ Команда PLAY распознана!")
            mediaController.togglePlayPause()
            updateNotification("Воспроизведение переключено")
            return
        }
        
        // Остановить/пауза музыки
        if (matchesCommand(lowerText, triggerWords, listOf("пауза", "стоп", "stop", "pause", "останови музыку", "останови"), requireTrigger, shouldCheckWithoutTrigger)) {
            Log.d("VOICE", "✓ Команда PAUSE/STOP распознана!")
            mediaController.togglePlayPause()
            updateNotification("Воспроизведение остановлено")
            return
        }
        
        // Следующий трек (проверяем более длинные фразы первыми)
        if (matchesCommand(lowerText, triggerWords, listOf("следующий трек", "дальше трек", "next track"), requireTrigger, shouldCheckWithoutTrigger)) {
            Log.d("VOICE", "✓ Команда NEXT (длинная) распознана!")
            mediaController.nextTrack()
            updateNotification("Следующий трек")
            return
        }
        
        if (matchesCommand(lowerText, triggerWords, listOf("следующий", "дальше", "next", "далее", "некст"), requireTrigger, shouldCheckWithoutTrigger)) {
            Log.d("VOICE", "✓ Команда NEXT распознана!")
            mediaController.nextTrack()
            updateNotification("Следующий трек")
            return
        }
        
        // Предыдущий трек (проверяем более длинные фразы первыми)
        if (matchesCommand(lowerText, triggerWords, listOf("предыдущий трек", "назад трек", "previous track"), requireTrigger, shouldCheckWithoutTrigger)) {
            Log.d("VOICE", "✓ Команда PREVIOUS (длинная) распознана!")
            mediaController.previousTrack()
            updateNotification("Предыдущий трек")
            return
        }
        
        if (matchesCommand(lowerText, triggerWords, listOf("предыдущий", "назад", "previous", "вернись"), requireTrigger, shouldCheckWithoutTrigger)) {
            Log.d("VOICE", "✓ Команда PREVIOUS распознана!")
            mediaController.previousTrack()
            updateNotification("Предыдущий трек")
            return
        }
        
        // Если ни одна команда не найдена, логируем для отладки
        Log.d("VOICE_SERVICE", "Текст не распознан как команда: '$lowerText'")
    }
    
    /**
     * Проверка команды с учетом настройки триггерного слова
     * @param text распознанный текст
     * @param triggerWords список триггерных слов
     * @param commands список команд
     * @param requireTrigger требуется ли триггерное слово (из настроек)
     * @param forceWithoutTrigger если true, проверяем команды БЕЗ триггерного слова (после активации триггера)
     * @return true если команда найдена
     */
    private fun matchesCommand(text: String, triggerWords: List<String>, commands: List<String>, requireTrigger: Boolean, forceWithoutTrigger: Boolean = false): Boolean {
        // Если триггерное слово уже было активировано, проверяем команды БЕЗ триггера
        if (forceWithoutTrigger) {
            return matchesCommandWithoutTrigger(text, commands)
        }
        
        if (requireTrigger) {
            // Проверяем команды с триггерным словом
            return matchesCommandWithTrigger(text, triggerWords, commands)
        } else {
            // Проверяем команды без триггерного слова (простое совпадение)
            return matchesCommandWithoutTrigger(text, commands)
        }
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
     * Проверка команды без триггерного слова
     * Для коротких команд (<= 6 символов) используем точное совпадение слова
     * Для длинных команд используем поиск подстроки
     */
    private fun matchesCommandWithoutTrigger(text: String, commands: List<String>): Boolean {
        for (command in commands) {
            val lowerCommand = command.lowercase().trim()
            if (lowerCommand.length <= 6) {
                // Для коротких команд проверяем точное совпадение слова
                val words = text.split(Regex("\\s+")).map { it.trim() }
                if (words.any { it.equals(lowerCommand, ignoreCase = true) } ||
                    text.equals(lowerCommand, ignoreCase = true)) {
                    return true
                }
            } else {
                // Для длинных команд используем поиск подстроки
                if (text.contains(lowerCommand, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
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

    /**
     * Применяет текущие настройки микрофона (дальность, шумоподавление, источник, порог partial)
     * к экземпляру VoskRecognizer.
     */
    private fun applyMicSettingsToRecognizer() {
        val micRange = VoiceSettings.getMicRangeLevel(this)
        val nsEnabled = VoiceSettings.isNoiseSuppressionEnabled(this)
        val audioSourceMode = VoiceSettings.getAudioSourceMode(this)
        val minPartialLength = VoiceSettings.getMinPartialLength(this)

        Log.d(
            "VOICE_SERVICE",
            "Применяем настройки к VoskRecognizer: micRange=$micRange, nsEnabled=$nsEnabled, audioSourceMode=$audioSourceMode, minPartialLength=$minPartialLength"
        )

        voskRecognizer.setMicRange(micRange)
        voskRecognizer.setNoiseSuppressionEnabled(nsEnabled)
        voskRecognizer.setAudioSourceMode(audioSourceMode)
        voskRecognizer.setMinPartialLength(minPartialLength)
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Восстанавливаем громкость перед остановкой сервиса
        restoreVolumeRunnable?.let { restoreVolumeHandler.removeCallbacks(it) }
        mediaController.restoreVolume()
        
        voskRecognizer.destroy()
        Log.d("VOICE", "VoiceService onDestroy()")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "voice_recognition_channel"
        private const val NOTIFICATION_ID = 1
    }
}