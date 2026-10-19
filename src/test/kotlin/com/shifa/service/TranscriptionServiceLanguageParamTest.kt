package com.shifa.service

import com.shifa.config.OpenAiProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TranscriptionServiceLanguageParamTest {

    private fun service(sendLang: Boolean = true): TranscriptionService {
        val props = OpenAiProperties().apply {
            apiKey = "test-key"
            projectId = "test-project"
            model = "gpt-4o-mini"
            transcriptionSendLanguageParam = sendLang
        }
        return TranscriptionService(props)
    }

    @Test
    fun `gpt-4o-transcribe sends en and ru only`() {
        val svc = service()
        assertEquals("en", svc.openAiLanguageParam("en", "gpt-4o-transcribe"))
        assertEquals("ru", svc.openAiLanguageParam("ru", "gpt-4o-transcribe"))
        assertNull(svc.openAiLanguageParam("uz", "gpt-4o-transcribe"))
    }

    @Test
    fun `whisper sends uz hint`() {
        val svc = service()
        assertEquals("uz", svc.openAiLanguageParam("uz", "whisper-1"))
    }

    @Test
    fun `gpt-4o skips language when send flag disabled`() {
        val svc = service(sendLang = false)
        assertNull(svc.openAiLanguageParam("en", "gpt-4o-transcribe"))
    }

    @Test
    fun `without gemini client uzbek voice stays on openai path`() {
        val svc = service()
        org.junit.jupiter.api.Assertions.assertFalse(
            svc.shouldUseGeminiStt("uz", TranscriptionPurpose.VOICE_UPLOAD),
        )
        org.junit.jupiter.api.Assertions.assertFalse(
            svc.shouldUseGeminiStt("uz", TranscriptionPurpose.SCRIBE_PIPELINE),
        )
    }

    @Test
    fun `gemini client routes uzbek and auto-detect voice uploads but not english or scribe`() {
        val gemini = org.mockito.Mockito.mock(com.shifa.ai.speech.GeminiSpeechClient::class.java)
        org.mockito.Mockito.`when`(gemini.isAvailable()).thenReturn(true)
        val svc = TranscriptionService(
            OpenAiProperties().apply {
                apiKey = "test-key"
                projectId = "test-project"
                model = "gpt-4o-mini"
            },
            gemini,
        )
        org.junit.jupiter.api.Assertions.assertTrue(svc.shouldUseGeminiStt("uz", TranscriptionPurpose.VOICE_UPLOAD))
        org.junit.jupiter.api.Assertions.assertTrue(svc.shouldUseGeminiStt(null, TranscriptionPurpose.VOICE_UPLOAD))
        org.junit.jupiter.api.Assertions.assertFalse(svc.shouldUseGeminiStt("en", TranscriptionPurpose.VOICE_UPLOAD))
        org.junit.jupiter.api.Assertions.assertFalse(svc.shouldUseGeminiStt("ru", TranscriptionPurpose.VOICE_UPLOAD))
        org.junit.jupiter.api.Assertions.assertFalse(svc.shouldUseGeminiStt("uz", TranscriptionPurpose.SCRIBE_PIPELINE))
    }
}
