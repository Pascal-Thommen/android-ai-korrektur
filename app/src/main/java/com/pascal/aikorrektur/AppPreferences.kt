package com.pascal.aikorrektur

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Verwaltet lokale Einstellungen, Prompt-Vorlagen und den Gemini API-Key.
 */
object AppPreferences {
    private const val PREFS_NAME = "ai_korrektur_preferences"
    private const val KEY_API_KEY = "gemini_api_key"
    private const val KEY_MODEL = "gemini_model"
    private const val KEY_SYSTEM_PROMPT = "system_prompt"
    private const val KEY_CACHED_MODELS = "cached_models"

    const val DEFAULT_MODEL = "gemini-2.5-flash"

    // Prompt Vorlagen:
    const val PROMPT_SWISS = "Du bist ein professioneller Text-Korrektur-Assistent. Korrigiere Grammatik, Rechtschreibung, Zeichensetzung und Ausdruck des folgenden Textes. Behalte den ursprünglichen Sinn, Tonfall und die Sprache bei. Verwende in deutschem Text niemals das scharfe S (ß), sondern immer Doppel-S (ss). Antworte ausschliesslich mit dem korrigierten Text, ohne Erklärungen, ohne Begrüssung, ohne Formatierungszeichen und ohne Anführungszeichen."
    const val PROMPT_STANDARD = "Du bist ein professioneller Text-Korrektur-Assistent. Korrigiere Grammatik, Rechtschreibung, Zeichensetzung und Ausdruck des folgenden Textes. Behalte den ursprünglichen Sinn, Tonfall und die Sprache bei. Antworte ausschließlich mit dem korrigierten Text, ohne Erklärungen, ohne Begrüßung, ohne Formatierungszeichen und ohne Anführungszeichen."

    // Standard ist Schweizer Rechtschreibung (kein ß) für Pascal
    const val DEFAULT_PROMPT = PROMPT_SWISS

    private const val OLD_LEGACY_PROMPT = "Du bist ein professioneller Text-Korrektur-Assistent. Korrigiere Grammatik, Rechtschreibung, Zeichensetzung und Ausdruck des folgenden Textes. Behalte den ursprünglichen Sinn, Tonfall und die Sprache bei. Antworte ausschließlich mit dem korrigierten Text, ohne Erklärungen, ohne Begrüßung, ohne Formatierungszeichen und ohne Anführungszeichen."

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
        // Migration von frueheren Platzhaltern
        if (saved == "gemini-3.8-flash" || saved.isBlank()) {
            setModel(context, DEFAULT_MODEL)
            return DEFAULT_MODEL
        }
        return saved
    }

    fun setModel(context: Context, model: String) {
        getPrefs(context).edit().putString(KEY_MODEL, model.trim()).apply()
    }

    fun getSystemPrompt(context: Context): String {
        val saved = getPrefs(context).getString(KEY_SYSTEM_PROMPT, DEFAULT_PROMPT) ?: DEFAULT_PROMPT
        // Automatische Aktualisierung vom alten Standard-Prompt ohne Schweiz-Regel
        if (saved == OLD_LEGACY_PROMPT) {
            setSystemPrompt(context, DEFAULT_PROMPT)
            return DEFAULT_PROMPT
        }
        return saved
    }

    fun setSystemPrompt(context: Context, prompt: String) {
        getPrefs(context).edit().putString(KEY_SYSTEM_PROMPT, prompt.trim()).apply()
    }

    fun getCachedModels(context: Context): List<String> {
        val raw = getPrefs(context).getString(KEY_CACHED_MODELS, "") ?: ""
        if (raw.isBlank()) {
            return listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash", "gemini-flash-latest")
        }
        return try {
            val jsonArray = JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.optString(i, "")
                if (item.isNotBlank()) list.add(item)
            }
            if (list.isEmpty()) listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash") else list
        } catch (_: Exception) {
            listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash")
        }
    }

    fun setCachedModels(context: Context, models: List<String>) {
        val jsonArray = JSONArray()
        for (m in models) {
            jsonArray.put(m)
        }
        getPrefs(context).edit().putString(KEY_CACHED_MODELS, jsonArray.toString()).apply()
    }

    fun isConfigured(context: Context): Boolean {
        return getApiKey(context).isNotBlank()
    }
}
