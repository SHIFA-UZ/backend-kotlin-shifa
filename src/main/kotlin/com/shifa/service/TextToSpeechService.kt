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
 * OpenAI TTS for short spoken replies in the doctor morning / Intelligence session.
 *
 * Intelligence path prioritizes latency: single fast model ([tts-1]), short input,
 * and a tight read timeout — no multi-model fallback chain that can hang for minutes.
 */
@Service
class TextToSpeechService(
    private val props: OpenAiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(8))
        .writeTimeout(Duration.ofSeconds(8))
        .readTimeout(Duration.ofSeconds(12))
        .build()

    data class SpeechResult(
        val audioBytes: ByteArray,
        val contentType: String,
    )

    fun speak(text: String, language: OutputLanguage = OutputLanguage.UZ): SpeechResult {
        val cleaned = truncateForSpeech(text.trim())
        if (cleaned.isBlank()) {
            throw IllegalArgumentException("text is required")
        }
        if (props.apiKey.isBlank()) {
            throw IllegalStateException("OpenAI API key is not configured")
        }

        // Fast path only — Intelligence must not wait on gpt-4o-mini-tts / HD fallbacks.
        val model = props.ttsModel.ifBlank { "tts-1" }.let { configured ->
            when {
                configured.equals("tts-1", ignoreCase = true) -> "tts-1"
                configured.equals("tts-1-hd", ignoreCase = true) -> "tts-1" // force fast
                configured.contains("gpt-4o", ignoreCase = true) -> "tts-1"
                else -> "tts-1"
            }
        }
        val voice = voiceForModel(model, props.ttsVoice)
        return callSpeechApi(
            text = cleaned,
            language = language,
            model = model,
            voice = voice,
            withInstructions = false,
        )
    }

    /**
     * Keep screen text full on the client; only speak a short lead so TTS stays snappy.
     */
    internal fun truncateForSpeech(text: String, maxChars: Int = 280): String {
        if (text.length <= maxChars) return text
        val cut = text.take(maxChars)
        val sentenceEnd = listOf('.', '!', '?')
            .map { cut.lastIndexOf(it) }
            .filter { it >= 40 }
            .maxOrNull()
        if (sentenceEnd != null) {
            val first = cut.substring(0, sentenceEnd + 1).trim()
            // Prefer up to two short sentences when they fit.
            val rest = text.substring(sentenceEnd + 1).trim()
            if (rest.isEmpty()) return first
            val secondCut = rest.take(maxChars - first.length - 1)
            val secondEnd = listOf('.', '!', '?')
                .map { secondCut.lastIndexOf(it) }
                .filter { it >= 20 }
                .maxOrNull()
            return if (secondEnd != null && first.length + secondEnd + 1 <= maxChars) {
                "$first ${secondCut.substring(0, secondEnd + 1).trim()}"
            } else {
                first
            }
        }
        val space = cut.lastIndexOf(' ')
        return (if (space > 40) cut.substring(0, space) else cut).trim()
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

        if (props.projectId.isNotBlank()) {
            requestBuilder.addHeader("OpenAI-Project", props.projectId)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string()?.take(400).orEmpty()
                throw IllegalStateException("TTS HTTP ${response.code}: $err")
            }
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (bytes.isEmpty()) {
                throw IllegalStateException("Empty speech audio from $model")
            }
            log.info("TTS ok model={} bytes={} lang={} chars={}", model, bytes.size, language.name, text.length)
            return SpeechResult(audioBytes = bytes, contentType = "audio/mpeg")
        }
    }
}
