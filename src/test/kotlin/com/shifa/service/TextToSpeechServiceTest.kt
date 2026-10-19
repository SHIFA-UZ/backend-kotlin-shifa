package com.shifa.service

import com.shifa.ai.OutputLanguage
import com.shifa.ai.speech.GeminiSpeechClient
import com.shifa.config.OpenAiProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class TextToSpeechServiceTest {
    @Test
    fun `uzbek uses gemini when available`() {
        val gemini = mock(GeminiSpeechClient::class.java)
        `when`(gemini.isAvailable()).thenReturn(true)
        `when`(gemini.speak(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(GeminiSpeechClient.SpeechAudio(byteArrayOf(1, 2, 3), "audio/wav"))
        val svc = TextToSpeechService(
            OpenAiProperties().apply {
                apiKey = "sk"
                projectId = ""
                model = "gpt-4o-mini"
            },
            gemini,
        )
        val out = svc.speak("Salom doktor", OutputLanguage.UZ)
        assertEquals("audio/wav", out.contentType)
        assertEquals(3, out.audioBytes.size)
        verify(gemini).speak(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun `english does not call gemini`() {
        val gemini = mock(GeminiSpeechClient::class.java)
        `when`(gemini.isAvailable()).thenReturn(true)
        val svc = TextToSpeechService(
            OpenAiProperties().apply {
                apiKey = ""
                projectId = ""
                model = "gpt-4o-mini"
            },
            gemini,
        )
        val ex = runCatching { svc.speak("Hello doctor", OutputLanguage.EN) }
        verify(gemini, never()).speak(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())
        org.junit.jupiter.api.Assertions.assertTrue(ex.exceptionOrNull() is IllegalStateException)
    }
}
