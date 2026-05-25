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
}
