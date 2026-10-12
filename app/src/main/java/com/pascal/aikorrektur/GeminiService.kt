package com.pascal.aikorrektur

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Service für blitzschnelle Anfragen an die Google Gemini REST API.
 * Mit automatischer Thinking-Abschaltung für Sub-Sekunden-Latenz und intelligenter Modell-Selbstheilung.
 */
object GeminiService {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    private val FAST_FALLBACK_MODELS = listOf(
        "gemini-2.5-flash",
        "gemini-2.0-flash",
        "gemini-1.5-flash",
        "gemini-flash-latest"
    )

    /**
     * Ruft alle aktuell für den API-Key verfügbaren Gemini-Modelle dynamisch von Google ab.
     */
    suspend fun fetchAvailableModels(apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Kein API-Key vorhanden."))
        }
        try {
            val url = URL("$BASE_URL?key=$apiKey")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                connectTimeout = 6000
                readTimeout = 10000
            }

            if (connection.responseCode in 200..299) {
                val responseBody = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
                    .use { it.readText() }
                val json = JSONObject(responseBody)
                val modelsArray = json.optJSONArray("models") ?: JSONArray()
                val resultList = mutableListOf<String>()

                for (i in 0 until modelsArray.length()) {
                    val m = modelsArray.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    var canGenerate = false
                    if (methods != null) {
                        for (j in 0 until methods.length()) {
                            if (methods.optString(j) == "generateContent") {
                                canGenerate = true
                                break
                            }
                        }
                    }
                    if (canGenerate) {
                        val rawName = m.optString("name", "")
                        val cleanName = rawName.removePrefix("models/")
                        if (cleanName.startsWith("gemini") &&
                            !cleanName.contains("embedding") &&
                            !cleanName.contains("image") &&
                            !cleanName.contains("tts") &&
                            !cleanName.contains("transcribe")
                        ) {
                            resultList.add(cleanName)
                        }
                    }
                }

                if (resultList.isEmpty()) {
                    Result.success(FAST_FALLBACK_MODELS)
                } else {
                    // Sortieren: Flash-Modelle nach oben priorisieren
                    resultList.sortWith(Comparator { a, b ->
                        when {
                            a.contains("flash") && !b.contains("flash") -> -1
                            !a.contains("flash") && b.contains("flash") -> 1
                            else -> b.compareTo(a)
                        }
                    })
                    Result.success(resultList)
                }
            } else {
                Result.failure(Exception("HTTP ${connection.responseCode} beim Abrufen der Modelle"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun correctText(
        apiKey: String,
        inputText: String,
        model: String = AppPreferences.DEFAULT_MODEL,
        systemInstruction: String = AppPreferences.DEFAULT_PROMPT,
        onFallbackModelUsed: ((String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("Gemini API-Key fehlt. Bitte in den Einstellungen hinterlegen.")
            )
        }

        val trimmedInput = inputText.trim()
        if (trimmedInput.isEmpty()) {
            return@withContext Result.success(inputText)
        }

        val cleanModel = model.trim().removePrefix("models/").ifBlank { AppPreferences.DEFAULT_MODEL }

        // 1. Primärer Aufruf mit dem gewählten Modell (mit deaktiviertem Thinking für maximale Geschwindigkeit)
        var firstResult = callGenerateContent(apiKey, trimmedInput, cleanModel, systemInstruction, disableThinking = true)

        // Falls Fehler durch thinkingConfig auftritt (z.B. Modell unterstützt thinkingConfig nicht): sofort ohne wiederholen
        val firstError = firstResult.exceptionOrNull()?.message.orEmpty()
        if (firstResult.isFailure && (firstError.contains("thinkingConfig") || firstError.contains("thinkingBudget") || firstError.contains("INVALID_ARGUMENT"))) {
            firstResult = callGenerateContent(apiKey, trimmedInput, cleanModel, systemInstruction, disableThinking = false)
        }

        if (firstResult.isSuccess) {
            return@withContext firstResult
        }

        // 2. Automatisches Self-Healing: Wenn Modell nicht existiert (404), timeoutet oder Fehler wirft
        for (fallback in FAST_FALLBACK_MODELS) {
            if (fallback == cleanModel) continue
            var fallbackResult = callGenerateContent(apiKey, trimmedInput, fallback, systemInstruction, disableThinking = true)
            val fallbackError = fallbackResult.exceptionOrNull()?.message.orEmpty()
            if (fallbackResult.isFailure && (fallbackError.contains("thinkingConfig") || fallbackError.contains("thinkingBudget") || fallbackError.contains("INVALID_ARGUMENT"))) {
                fallbackResult = callGenerateContent(apiKey, trimmedInput, fallback, systemInstruction, disableThinking = false)
            }
            if (fallbackResult.isSuccess) {
                onFallbackModelUsed?.invoke(fallback)
                return@withContext fallbackResult
            }
        }

        return@withContext firstResult
    }

    private fun callGenerateContent(
        apiKey: String,
        trimmedInput: String,
        model: String,
        systemInstruction: String,
        disableThinking: Boolean = true
    ): Result<String> {
        return try {
            val endpoint = "$BASE_URL/$model:generateContent?key=$apiKey"
            val url = URL(endpoint)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 7000
                readTimeout = 14000
                doOutput = true
                doInput = true
            }

            val generationConfig = JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 1024)
                // Bei modernen Gemini Modellen Thinking-Budget auf 0 setzen -> keine Denkpause, sofortige Antwort
                if (disableThinking && !model.startsWith("gemini-1.")) {
                    put("thinkingConfig", JSONObject().apply {
                        put("thinkingBudget", 0)
                    })
                }
            }

            val payload = JSONObject().apply {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", systemInstruction)
                        })
                    })
                })
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", trimmedInput)
                            })
                        })
                    })
                })
                put("generationConfig", generationConfig)
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val responseBody = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
                    .use { it.readText() }

                val responseJson = JSONObject(responseBody)
                val candidates = responseJson.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val candidate = candidates.getJSONObject(0)
                    val content = candidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val textBuilder = StringBuilder()
                        for (p in 0 until parts.length()) {
                            val partObj = parts.getJSONObject(p)
                            // Falls Thought-Part existiert, überspringen und nur den finalen Text nehmen
                            if (!partObj.optBoolean("thought", false)) {
                                val t = partObj.optString("text", "")
                                if (t.isNotEmpty()) textBuilder.append(t)
                            }
                        }
                        if (textBuilder.isNotEmpty()) {
                            return Result.success(textBuilder.toString().trim())
                        }
                        // Fallback falls keine thought-Flags
                        val directText = parts.getJSONObject(0).optString("text", "")
                        if (directText.isNotBlank()) {
                            return Result.success(directText.trim())
                        }
                    }
                }
                Result.failure(Exception("Keine Antwort von Gemini erhalten."))
            } else {
                val errorBody = connection.errorStream?.let { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                } ?: "HTTP $responseCode"

                val message = try {
                    val errJson = JSONObject(errorBody)
                    val errObj = errJson.optJSONObject("error")
                    errObj?.optString("message", errorBody) ?: errorBody
                } catch (e: Exception) {
                    errorBody
                }

                Result.failure(Exception("API Fehler ($responseCode): $message"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
