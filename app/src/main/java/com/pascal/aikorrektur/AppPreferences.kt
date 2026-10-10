package com.pascal.aikorrektur

import android.content.Context
import android.content.SharedPreferences

/**
 * Verwaltet lokale Einstellungen und den Gemini API-Key.
 */
object AppPreferences {
    private const val PREFS_NAME = "ai_korrektur_preferences"
    private const val KEY_API_KEY = "gemini_api_key"
    private const val KEY_MODEL = "gemini_model"
    private const val KEY_SYSTEM_PROMPT = "system_prompt"

    const val DEFAULT_MODEL = "gemini-3.8-flash"
    const val DEFAULT_PROMPT = "Du bist ein professioneller Text-Korrektur-Assistent. Korrigiere Grammatik, Rechtschreibung, Zeichensetzung und Ausdruck des folgenden Textes. Behalte den ursprünglichen Sinn, Tonfall und die Sprache bei. Antworte ausschließlich mit dem korrigierten Text, ohne Erklärungen, ohne Begrüßung, ohne Formatierungszeichen und ohne Anführungszeichen."

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getApiKey(context: Context): String {
        return getPrefs(context).getString(KEY_API_KEY, "")?.trim().orEmpty()
    }

    fun setApiKey(context: Context, apiKey: String) {
        getPrefs(context).edit().putString(KEY_API_KEY, apiKey.trim()).apply()
    }

    fun getModel(context: Context): String {
        val saved = getPrefs(context).getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        if (saved == "gemini-2.0-flash" || saved == "gemini-1.5-flash" || saved == "gemini-1.5-pro") {
            setModel(context, DEFAULT_MODEL)
            return DEFAULT_MODEL
        }
        return saved
    }

    fun setModel(context: Context, model: String) {
        getPrefs(context).edit().putString(KEY_MODEL, model.trim()).apply()
    }

    fun getSystemPrompt(context: Context): String {
        return getPrefs(context).getString(KEY_SYSTEM_PROMPT, DEFAULT_PROMPT) ?: DEFAULT_PROMPT
    }

    fun setSystemPrompt(context: Context, prompt: String) {
        getPrefs(context).edit().putString(KEY_SYSTEM_PROMPT, prompt.trim()).apply()
    }

    fun isConfigured(context: Context): Boolean {
        return getApiKey(context).isNotBlank()
    }
}
