package com.example.offlinespeech

import android.content.Context
import android.content.SharedPreferences

/**
 * Класс для управления настройками голосового помощника
 */
object VoiceSettings {
    private const val PREFS_NAME = "voice_helper_prefs"
    private const val KEY_REQUIRE_TRIGGER_WORD = "require_trigger_word"
    private const val KEY_MIC_RANGE_LEVEL = "mic_range_level"
    private const val KEY_NOISE_SUPPRESSION_ENABLED = "noise_suppression_enabled"
    private const val KEY_AUDIO_SOURCE_MODE = "audio_source_mode"
    private const val KEY_MIN_PARTIAL_LENGTH = "min_partial_length"
    
    /**
     * Получить SharedPreferences
     */
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Проверить, требуется ли триггерное слово
     */
    fun isTriggerWordRequired(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_REQUIRE_TRIGGER_WORD, true) // По умолчанию требуется
    }
    
    /**
     * Установить, требуется ли триггерное слово
     */
    fun setTriggerWordRequired(context: Context, required: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_REQUIRE_TRIGGER_WORD, required).apply()
    }

    /**
     * Уровень \"дальности\" микрофона (0–10), по умолчанию 5
     */
    fun getMicRangeLevel(context: Context): Int {
        return getPrefs(context).getInt(KEY_MIC_RANGE_LEVEL, 5).coerceIn(0, 10)
    }

    fun setMicRangeLevel(context: Context, level: Int) {
        getPrefs(context).edit()
            .putInt(KEY_MIC_RANGE_LEVEL, level.coerceIn(0, 10))
            .apply()
    }

    /**
     * Включено ли шумоподавление (NS/AEC/AGC), по умолчанию true
     */
    fun isNoiseSuppressionEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_NOISE_SUPPRESSION_ENABLED, true)
    }

    fun setNoiseSuppressionEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit()
            .putBoolean(KEY_NOISE_SUPPRESSION_ENABLED, enabled)
            .apply()
    }

    /**
     * Режим источника аудио:
     * 0 — VOICE_RECOGNITION, 1 — VOICE_COMMUNICATION
     */
    fun getAudioSourceMode(context: Context): Int {
        return getPrefs(context).getInt(KEY_AUDIO_SOURCE_MODE, VoskRecognizer.AUDIO_SOURCE_MODE_VOICE_RECOGNITION)
    }

    fun setAudioSourceMode(context: Context, mode: Int) {
        val safeMode = when (mode) {
            VoskRecognizer.AUDIO_SOURCE_MODE_VOICE_COMMUNICATION ->
                VoskRecognizer.AUDIO_SOURCE_MODE_VOICE_COMMUNICATION
            else ->
                VoskRecognizer.AUDIO_SOURCE_MODE_VOICE_RECOGNITION
        }
        getPrefs(context).edit()
            .putInt(KEY_AUDIO_SOURCE_MODE, safeMode)
            .apply()
    }

    /**
     * Минимальная длина partial-результата (1–6), по умолчанию 3
     */
    fun getMinPartialLength(context: Context): Int {
        val value = getPrefs(context).getInt(KEY_MIN_PARTIAL_LENGTH, 3)
        return value.coerceIn(1, 6)
    }

    fun setMinPartialLength(context: Context, length: Int) {
        val safe = length.coerceIn(1, 6)
        getPrefs(context).edit()
            .putInt(KEY_MIN_PARTIAL_LENGTH, safe)
            .apply()
    }
}

