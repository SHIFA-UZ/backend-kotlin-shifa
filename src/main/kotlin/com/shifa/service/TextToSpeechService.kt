package com.shifa.service

import com.shifa.ai.OutputLanguage
import com.shifa.config.OpenAiProperties
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * OpenAI TTS for short spoken replies in the doctor morning session.
 * Browser speech synthesis is weak for Uzbek; server TTS is required for the demo path.
 */
@Service
class TextToSpeechService(
    private val props: OpenAiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(20))
        .writeTimeout(Duration.ofSeconds(20))
        .readTimeout(Duration.ofSeconds(60))
        .build()

    data class SpeechResult(
        val audioBytes: ByteArray,
        val contentType: String,
    )

    fun speak(text: String, language: OutputLanguage = OutputLanguage.UZ): SpeechResult {
        val cleaned = text.trim().take(2200)
        if (cleaned.isBlank()) {
            throw IllegalArgumentException("text is required")
        }
        if (props.apiKey.isBlank()) {
            throw IllegalStateException("OpenAI API key is not configured")
        }

        val voice = props.ttsVoice.ifBlank { "nova" }
        val model = props.ttsModel.ifBlank { "gpt-4o-mini-tts" }
        val instructions = when (language) {
            OutputLanguage.UZ -> "Speak clearly in Uzbek. Calm, professional clinic assistant tone. Short sentences."
            OutputLanguage.RU -> "Говорите ясно по-русски. Спокойный профессиональный тон клинического ассистента."
            OutputLanguage.EN -> "Speak clearly in English. Calm, professional clinic assistant tone. Short sentences."
        }

        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to model,
                "voice" to voice,
                "input" to cleaned,
                "instructions" to instructions,
                "response_format" to "mp3",
            )
        )

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/speech")
            .addHeader("Authorization", "Bearer ${props.apiKey}")
            .addHeader("OpenAI-Project", props.projectId)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string()?.take(300)
                log.warn("TTS failed HTTP {}: {}", response.code, err)
                throw IllegalStateException("Speech synthesis failed")
            }
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (bytes.isEmpty()) throw IllegalStateException("Empty speech audio")
            return SpeechResult(audioBytes = bytes, contentType = "audio/mpeg")
        }
    }
}
