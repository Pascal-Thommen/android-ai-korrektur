package com.pascal.aikorrektur

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
                put("maxOutputTokens", 4096)
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
    }
}
