package com.shifa.ai.infrastructure

import tools.jackson.module.kotlin.jacksonObjectMapper
import com.shifa.ai.application.AiRoutingProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class GeminiSpeechClientTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `extracts transcript from interactions steps`() {
        val json = mapper.readTree(
            """
            {"status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"Bugun uchta qabul"}]}]}
            """.trimIndent(),
        )
        assertEquals("Bugun uchta qabul", GeminiSpeechClient.extractText(json))
    }

    @Test
    fun `extracts wav from output_audio data`() {
        val wav = byteArrayOf(82, 73, 70, 70, 1, 2, 3, 4)
        val b64 = Base64.getEncoder().encodeToString(wav)
        val json = mapper.readTree("""{"output_audio":{"data":"$b64"}}""")
        assertTrue(GeminiSpeechClient.extractAudio(json) contentEquals wav)
    }

    @Test
    fun `unavailable without gemini key`() {
        val props = AiRoutingProperties()
        val client = GeminiSpeechClient(props)
        assertFalse(client.isAvailable())
    }

    @Test
    fun `available when gemini is enabled and keyed`() {
        val props = AiRoutingProperties().apply {
            providers["gemini"] = AiRoutingProperties.Provider().apply {
                enabled = true
                apiKey = "g-key"
                chatModel = "gemini-2.5-flash"
                baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai"
            }
        }
        assertTrue(GeminiSpeechClient(props).isAvailable())
    }
}
