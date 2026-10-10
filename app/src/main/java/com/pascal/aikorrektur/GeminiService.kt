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
 * Service für Anfragen an die Google Gemini REST API.
 */
object GeminiService {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    suspend fun correctText(
        apiKey: String,
        inputText: String,
        model: String = AppPreferences.DEFAULT_MODEL,
        systemInstruction: String = AppPreferences.DEFAULT_PROMPT
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

        try {
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
                // System Instruction
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", systemInstruction)
                        })
                    })
                })

                // Contents
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", trimmedInput)
                            })
                        })
                    })
                })

                // Generation Config
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
                        return@withContext Result.success(corrected.trim())
                    }
                }
                return@withContext Result.failure(Exception("Keine Antwort von Gemini erhalten."))
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

                return@withContext Result.failure(Exception("API Fehler ($responseCode): $message"))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
}
