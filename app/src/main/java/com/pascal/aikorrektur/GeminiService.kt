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
 * Service für Anfragen an die Google Gemini REST & Interactions API.
 * Beinhaltet dynamische Modellabfrage und automatische Modell-Selbstheilung.
 */
object GeminiService {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    private const val INTERACTIONS_URL = "https://generativelanguage.googleapis.com/v1beta/interactions"

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
                connectTimeout = 10000
                readTimeout = 15000
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
                    Result.success(listOf("gemini-3.8-flash", "gemini-flash-latest", "gemini-3.5-flash-lite"))
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

        // 1. Primärer Aufruf mit dem gewählten Modell
        val firstResult = executeGeneration(apiKey, trimmedInput, cleanModel, systemInstruction)
        if (firstResult.isSuccess) {
            return@withContext firstResult
        }

        // 2. Prüfen, ob Fehler durch ein nicht mehr existierendes Modell ausgelöst wurde (404 / no longer available)
        val errorMessage = firstResult.exceptionOrNull()?.message.orEmpty()
        val isModelUnavailable = errorMessage.contains("404") ||
                errorMessage.contains("no longer available") ||
                errorMessage.contains("not found")

        if (isModelUnavailable) {
            // Dynamisch verfügbare Modelle abfragen oder Standard-Fallback nutzen
            val liveModels = fetchAvailableModels(apiKey).getOrNull()
            val fallbackModel = liveModels?.firstOrNull { it != cleanModel } ?: "gemini-3.8-flash"

            val fallbackResult = executeGeneration(apiKey, trimmedInput, fallbackModel, systemInstruction)
            if (fallbackResult.isSuccess) {
                onFallbackModelUsed?.invoke(fallbackModel)
                return@withContext fallbackResult
            }
        }

        return@withContext firstResult
    }

    private fun executeGeneration(
        apiKey: String,
        trimmedInput: String,
        model: String,
        systemInstruction: String
    ): Result<String> {
        val generateContentResult = callGenerateContent(apiKey, trimmedInput, model, systemInstruction)
        if (generateContentResult.isSuccess) {
            return generateContentResult
        }

        val interactionsResult = callInteractionsApi(apiKey, trimmedInput, model, systemInstruction)
        if (interactionsResult.isSuccess) {
            return interactionsResult
        }

        return generateContentResult
    }

    private fun callGenerateContent(
        apiKey: String,
        trimmedInput: String,
        model: String,
        systemInstruction: String
    ): Result<String> {
        return try {
            val endpoint = "$BASE_URL/$model:generateContent?key=$apiKey"
            val url = URL(endpoint)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 12000
                readTimeout = 20000
                doOutput = true
                doInput = true
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
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", 4096)
                })
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
                        val corrected = parts.getJSONObject(0).optString("text", "")
                        return Result.success(corrected.trim())
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

    private fun callInteractionsApi(
        apiKey: String,
        trimmedInput: String,
        model: String,
        systemInstruction: String
    ): Result<String> {
        return try {
            val endpoint = "$INTERACTIONS_URL?key=$apiKey"
            val url = URL(endpoint)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
                setRequestProperty("Api-Revision", "2026-05-20")
                connectTimeout = 12000
                readTimeout = 20000
                doOutput = true
                doInput = true
            }

            val payload = JSONObject().apply {
                put("model", model)
                put("input", trimmedInput)
                put("system_instruction", systemInstruction)
                put("store", false)
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
                val directOutput = responseJson.optString("output_text", "")
                if (directOutput.isNotBlank()) {
                    return Result.success(directOutput.trim())
                }

                val steps = responseJson.optJSONArray("steps")
                if (steps != null && steps.length() > 0) {
                    for (i in steps.length() - 1 downTo 0) {
                        val step = steps.optJSONObject(i) ?: continue
                        if (step.optString("type") == "model_output") {
                            val content = step.optJSONArray("content")
                            if (content != null && content.length() > 0) {
                                val text = content.getJSONObject(0).optString("text", "")
                                if (text.isNotBlank()) {
                                    return Result.success(text.trim())
                                }
                            }
                        }
                    }
                }
                Result.failure(Exception("Keine Text-Antwort in Interactions API gefunden."))
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

                Result.failure(Exception("Interactions API Fehler ($responseCode): $message"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
