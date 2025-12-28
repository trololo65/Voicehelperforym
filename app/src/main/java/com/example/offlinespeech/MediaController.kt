package com.example.offlinespeech

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.KeyEvent

/**
 * Класс для управления медиа через эмуляцию нажатий кнопок (как на наушниках)
 */
class MediaController(private val context: Context) {

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /**
     * Воспроизвести/пауза музыки (эмуляция нажатия кнопки play/pause на наушниках)
     */
    fun togglePlayPause() {
        try {
            Log.d("MediaController", "Переключение play/pause")
            sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        } catch (e: Exception) {
            Log.e("MediaController", "Ошибка при переключении play/pause", e)
        }
    }

    /**
     * Следующий трек
     */
    fun nextTrack() {
        try {
            Log.d("MediaController", "Следующий трек")
            sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
        } catch (e: Exception) {
            Log.e("MediaController", "Ошибка при переходе к следующему треку", e)
        }
    }

    /**
     * Предыдущий трек
     */
    fun previousTrack() {
        try {
            Log.d("MediaController", "Предыдущий трек")
            sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        } catch (e: Exception) {
            Log.e("MediaController", "Ошибка при переходе к предыдущему треку", e)
        }
    }

    /**
     * Остановить музыку
     */
    fun stop() {
        try {
            Log.d("MediaController", "Остановка музыки")
            sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_STOP)
        } catch (e: Exception) {
            Log.e("MediaController", "Ошибка при остановке музыки", e)
        }
    }

    /**
     * Уменьшить громкость музыки (для улучшения распознавания речи)
     * Устаревший метод, используйте decreaseVolume() или setMinVolume()
     */
    fun lowerVolume() {
        try {
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            // Уменьшаем громкость до примерно 30% от максимума
            val targetVolume = (maxVolume * 0.3).toInt().coerceAtLeast(1)
            
            if (currentVolume > targetVolume) {
                Log.d("MediaController", "Уменьшение громкости с $currentVolume до $targetVolume")
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    targetVolume,
                    AudioManager.FLAG_SHOW_UI
                )
            } else {
                Log.d("MediaController", "Громкость уже низкая ($currentVolume)")
            }
        } catch (e: Exception) {
            Log.e("MediaController", "Ошибка при уменьшении громкости", e)
        }
    }

    /**
     * Увеличить громкость музыки
     */
    fun increaseVolume() {
        try {
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val step = (maxVolume * 0.2).toInt().coerceAtLeast(1) // Увеличиваем на 20%
            val targetVolume = (currentVolume + step).coerceAtMost(maxVolume)
            
            if (currentVolume < maxVolume) {
                Log.d("MediaController", "Увеличение громкости с $currentVolume до $targetVolume")
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    targetVolume,
                    AudioManager.FLAG_SHOW_UI
                )
            } else {
                Log.d("MediaController", "Громкость уже максимальная ($currentVolume)")
            }
        } catch (e: Exception) {
            Log.e("MediaController", "Ошибка при увеличении громкости", e)
        }
    }

    /**
     * Уменьшить громкость музыки
     */
    fun decreaseVolume() {
        try {
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val step = (maxVolume * 0.2).toInt().coerceAtLeast(1) // Уменьшаем на 20%
            val targetVolume = (currentVolume - step).coerceAtLeast(1)
            
            if (currentVolume > 1) {
                Log.d("MediaController", "Уменьшение громкости с $currentVolume до $targetVolume")
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    targetVolume,
                    AudioManager.FLAG_SHOW_UI
                )
            } else {
                Log.d("MediaController", "Громкость уже минимальная ($currentVolume)")
            }
        } catch (e: Exception) {
            Log.e("MediaController", "Ошибка при уменьшении громкости", e)
        }
    }

    /**
     * Установить максимальную громкость
     */
    fun setMaxVolume() {
        try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            Log.d("MediaController", "Установка максимальной громкости: $maxVolume")
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                maxVolume,
                AudioManager.FLAG_SHOW_UI
            )
        } catch (e: Exception) {
            Log.e("MediaController", "Ошибка при установке максимальной громкости", e)
        }
    }

    /**
     * Установить минимальную громкость (10%)
     */
    fun setMinVolume() {
        try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVolume = (maxVolume * 0.1).toInt().coerceAtLeast(1)
            Log.d("MediaController", "Установка минимальной громкости (10%): $targetVolume")
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                targetVolume,
                AudioManager.FLAG_SHOW_UI
            )
        } catch (e: Exception) {
            Log.e("MediaController", "Ошибка при установке минимальной громкости", e)
        }
    }

    /**
     * Отправка медиа-кнопки через KeyEvent (эмуляция нажатия на наушниках)
     */
    private fun sendMediaKeyEvent(keyCode: Int) {
        try {
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)

            // Отправляем события через AudioManager (современный способ)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                audioManager.dispatchMediaKeyEvent(downEvent)
                audioManager.dispatchMediaKeyEvent(upEvent)
            } else {
                // Для старых версий Android используем альтернативный способ
                val intentDown = android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON).apply {
                    putExtra(android.content.Intent.EXTRA_KEY_EVENT, downEvent)
                }
                context.sendBroadcast(intentDown)
                
                val intentUp = android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON).apply {
                    putExtra(android.content.Intent.EXTRA_KEY_EVENT, upEvent)
                }
                context.sendBroadcast(intentUp)
            }
        } catch (e: Exception) {
            Log.e("MediaController", "Ошибка отправки медиа-кнопки", e)
        }
    }
}

