package com.pascal.aikorrektur

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Manages preferences, Gemini API key, model selection, and custom prompt instructions.
 */
object AppPreferences {
    private const val PREFS_NAME = "ai_korrektur_preferences"
    private const val KEY_API_KEY = "gemini_api_key"
    private const val KEY_MODEL = "gemini_model"
    private const val KEY_CUSTOM_INSTRUCTIONS = "custom_instructions"
    private const val KEY_LEGACY_PROMPT = "system_prompt"
    private const val KEY_CACHED_MODELS = "cached_models"

    const val DEFAULT_MODEL = "gemini-2.5-flash"

    const val BASE_SYSTEM_PROMPT = "You are a professional proofreading assistant. Correct grammar, spelling, punctuation, and wording of the input text. Maintain the original language, tone, and meaning. Output only the corrected text without explanations, intros, formatting, or quotation marks."

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
        if (saved == "gemini-3.8-flash" || saved.isBlank()) {
            setModel(context, DEFAULT_MODEL)
            return DEFAULT_MODEL
        }
        return saved
    }

    fun setModel(context: Context, model: String) {
        getPrefs(context).edit().putString(KEY_MODEL, model.trim()).apply()
    }

    fun getCustomInstructions(context: Context): String {
        val prefs = getPrefs(context)
        val custom = prefs.getString(KEY_CUSTOM_INSTRUCTIONS, null)
        if (custom != null) {
            return custom.trim()
        }
        // Migration von altem system_prompt falls vorhanden
        val legacy = prefs.getString(KEY_LEGACY_PROMPT, "")?.trim().orEmpty()
        if (legacy.isNotBlank() && !legacy.startsWith("You are a professional") && !legacy.startsWith("Du bist ein professioneller")) {
            setCustomInstructions(context, legacy)
            return legacy
        }
        return ""
    }

    fun setCustomInstructions(context: Context, instructions: String) {
        getPrefs(context).edit().putString(KEY_CUSTOM_INSTRUCTIONS, instructions.trim()).apply()
    }

    /**
     * Builds the complete effective prompt combining the universal base prompt and custom user instructions.
     */
    fun getEffectivePrompt(context: Context): String {
        val custom = getCustomInstructions(context)
        return if (custom.isNotBlank()) {
            "$BASE_SYSTEM_PROMPT\n\nAdditional user rules:\n$custom"
        } else {
            BASE_SYSTEM_PROMPT
        }
    }

    fun getSystemPrompt(context: Context): String = getEffectivePrompt(context)

    fun setSystemPrompt(context: Context, prompt: String) {
        setCustomInstructions(context, prompt)
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
