package com.example.offlinespeech

import android.content.Context
import android.content.SharedPreferences

/**
 * Класс для управления настройками голосового помощника
 */
object VoiceSettings {
    private const val PREFS_NAME = "voice_helper_prefs"
    private const val KEY_REQUIRE_TRIGGER_WORD = "require_trigger_word"
    
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
}

