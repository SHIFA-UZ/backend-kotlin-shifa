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
 *
 * Primary model: [gpt-4o-mini-tts] — OpenAI's newest /v1/audio/speech model (instruction-steerable).
 * Fallback: tts-1-hd → tts-1 if the project key cannot use gpt-4o-mini-tts.
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
        val cleaned = text.trim().take(2000)
        if (cleaned.isBlank()) {
            throw IllegalArgumentException("text is required")
        }
        if (props.apiKey.isBlank()) {
            throw IllegalStateException("OpenAI API key is not configured")
        }

        val preferred = props.ttsModel.ifBlank { "gpt-4o-mini-tts" }
        val attempts = linkedSetOf(
            preferred,
            "gpt-4o-mini-tts",
            "tts-1-hd",
            "tts-1",
        ).toList()

        var lastError: String? = null
        for (model in attempts) {
            val voice = voiceForModel(model, props.ttsVoice)
            try {
                return callSpeechApi(
                    text = cleaned,
                    language = language,
                    model = model,
                    voice = voice,
                    withInstructions = model.contains("gpt-4o", ignoreCase = true),
                )
            } catch (e: TtsAttemptException) {
                lastError = e.message
                log.warn("TTS model={} voice={} failed: {}", model, voice, e.message)
            }
        }
        throw IllegalStateException(lastError ?: "Speech synthesis failed")
    }

    /** Older tts-1* models do not support marin/cedar/verse. */
    private fun voiceForModel(model: String, configured: String): String {
        val voice = configured.ifBlank { "coral" }
        if (model.contains("gpt-4o", ignoreCase = true)) return voice
        val legacyOk = setOf(
            "alloy", "ash", "coral", "echo", "fable", "onyx", "nova", "sage", "shimmer",
        )
        return if (legacyOk.contains(voice.lowercase())) voice else "nova"
    }

    private fun callSpeechApi(
        text: String,
        language: OutputLanguage,
        model: String,
        voice: String,
        withInstructions: Boolean,
    ): SpeechResult {
        val body = linkedMapOf<String, Any>(
            "model" to model,
            "voice" to voice,
            "input" to text,
            "response_format" to "mp3",
        )
        if (withInstructions) {
            body["instructions"] = when (language) {
                OutputLanguage.UZ ->
                    "Speak clearly in Uzbek. Calm, professional clinic assistant tone. Short sentences."
                OutputLanguage.RU ->
                    "Говорите ясно по-русски. Спокойный профессиональный тон клинического ассистента. Короткие фразы."
                OutputLanguage.EN ->
                    "Speak clearly in English. Calm, professional clinic assistant tone. Short sentences."
            }
        }

        val payload = mapper.writeValueAsString(body)
        val requestBuilder = Request.Builder()
            .url("https://api.openai.com/v1/audio/speech")
            .addHeader("Authorization", "Bearer ${props.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))

        // Project header is required for sk-proj keys; skip if unset to avoid needless 401s.
        if (props.projectId.isNotBlank()) {
            requestBuilder.addHeader("OpenAI-Project", props.projectId)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string()?.take(400).orEmpty()
                throw TtsAttemptException("HTTP ${response.code}: $err")
            }
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (bytes.isEmpty()) {
                throw TtsAttemptException("Empty speech audio from $model")
            }
            log.info("TTS ok model={} bytes={} lang={}", model, bytes.size, language.name)
            return SpeechResult(audioBytes = bytes, contentType = "audio/mpeg")
        }
    }

    private class TtsAttemptException(message: String) : RuntimeException(message)
}
