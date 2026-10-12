package com.pascal.aikorrektur

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiJsonTest {

    @Test
    fun testGeminiPayloadStructure() {
        val inputText = "Das ist ein Tesst mit fehler."
        val systemInstruction = AppPreferences.DEFAULT_PROMPT

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
                            put("text", inputText)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 1024)
                put("thinkingConfig", JSONObject().apply {
                    put("thinkingBudget", 0)
                })
            })
        }

        assertNotNull(payload)
        val contents = payload.getJSONArray("contents")
        assertEquals(1, contents.length())
        val firstPart = contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0)
        assertEquals(inputText, firstPart.getString("text"))

        val sysInstruction = payload.getJSONObject("systemInstruction")
        val sysPart = sysInstruction.getJSONArray("parts").getJSONObject(0)
        assertEquals(systemInstruction, sysPart.getString("text"))

        val genConfig = payload.getJSONObject("generationConfig")
        val thinking = genConfig.getJSONObject("thinkingConfig")
        assertEquals(0, thinking.getInt("thinkingBudget"))
    }

    @Test
    fun testSwissPromptRequiresDoubleS() {
        val prompt = AppPreferences.PROMPT_SWISS
        assertTrue(prompt.contains("Doppel-S (ss)"))
        assertTrue(prompt.contains("niemals das scharfe S"))
        assertTrue(prompt.contains("ausschliesslich"))
        assertTrue(prompt.contains("Begrüssung"))
        assertFalse(prompt.contains("ausschließlich"))
        assertFalse(prompt.contains("Begrüßung"))
    }

    @Test
    fun testDefaultModelIsFastModel() {
        assertEquals("gemini-2.5-flash", AppPreferences.DEFAULT_MODEL)
    }
}
