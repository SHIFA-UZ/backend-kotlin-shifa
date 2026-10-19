package com.shifa.ai.speech

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.shifa.ai.llm.AiRoutingProperties
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.time.Duration
import java.util.Base64

/**
 * Gemini Interactions API for Intelligence voice (STT + TTS).
 * Not OpenAI-compatible — audio in/out, not chat completions.
 */
@Component
class GeminiSpeechClient(
    private val props: AiRoutingProperties,
    baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .writeTimeout(Duration.ofSeconds(60))
        .readTimeout(Duration.ofSeconds(90))
        .build(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()
    private val http = baseClient

    fun isAvailable(): Boolean {
        if (props.speech.uzbekProvider != "gemini") return false
        val g = props.providers["gemini"] ?: return false
        return g.enabled && g.apiKey.isNotBlank()
    }

    fun transcribe(file: File, mimeType: String, languageBcp47: String?): Transcription {
        val key = apiKey()
        val model = props.speech.sttModel.ifBlank { "gemini-3.5-transcribe" }
        log.info("gemini_stt_start model={} bytes={}", model, file.length())
        if (file.length() < 256) {
            throw IllegalStateException("audio too short")
        }
        val audioB64 = Base64.getEncoder().encodeToString(file.readBytes())
        val generation = linkedMapOf<String, Any>(
            "transcription_config" to buildMap {
                if (!languageBcp47.isNullOrBlank()) put("language_codes", listOf(languageBcp47))
                put("custom_vocabulary", listOf("Shifa", "qabul", "bemor"))
            },
        )
        val body = linkedMapOf(
            "model" to model,
            "input" to listOf(
                mapOf(
                    "type" to "audio",
                    "mime_type" to mimeType,
                    "data" to audioB64,
                ),
            ),
            "generation_config" to generation,
        )
        val json = postInteractions(key, body)
        val text = extractText(json).trim()
        if (text.isBlank()) {
            throw IllegalStateException("Gemini STT returned empty transcript")
        }
        log.info("gemini_stt model={} chars={} langHint={}", model, text.length, languageBcp47 ?: "auto")
        return Transcription(transcript = text, language = languageBcp47?.take(2))
    }

    fun speak(text: String, style: String): SpeechAudio {
        val key = apiKey()
        val model = props.speech.ttsModel.ifBlank { "gemini-3.8-flash-lite-tts" }
        val voice = props.speech.ttsVoice.ifBlank { "Kore" }
        val body = mapOf(
            "model" to model,
            "input" to listOf(
                mapOf(
                    "type" to "user_input",
                    "content" to listOf(
                        mapOf(
                            "type" to "text",
                            "text" to text,
                            "annotations" to listOf(
                                mapOf("type" to "speech_metadata", "style" to style),
                            ),
                        ),
                    ),
                ),
            ),
            "response_format" to mapOf("type" to "audio", "mime_type" to "audio/wav"),
            "generation_config" to mapOf(
                "speech_config" to listOf(mapOf("voice" to voice)),
            ),
        )
        val json = postInteractions(key, body)
        val bytes = extractAudio(json) ?: throw IllegalStateException("Gemini TTS returned no audio")
        log.info("gemini_tts model={} bytes={}", model, bytes.size)
        return SpeechAudio(bytes = bytes, contentType = "audio/wav")
    }

    private fun apiKey(): String {
        val key = props.providers["gemini"]?.apiKey.orEmpty()
        if (key.isBlank()) throw IllegalStateException("Gemini API key is not configured")
        return key
    }

    private fun postInteractions(apiKey: String, body: Map<String, Any?>): JsonNode {
        val payload = mapper.writeValueAsString(body)
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/interactions")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                log.warn("gemini_speech HTTP {} bodyChars={}", response.code, text.length)
                throw GeminiSpeechException(response.code, "HTTP ${response.code}")
            }
            return mapper.readTree(text)
        }
    }

    data class Transcription(val transcript: String, val language: String?)
    data class SpeechAudio(val bytes: ByteArray, val contentType: String)
    class GeminiSpeechException(val httpStatus: Int, message: String) : RuntimeException(message)

    companion object {
        fun extractText(json: JsonNode): String {
            json.path("output_text").asText(null)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            val parts = ArrayList<String>()
            for (step in json.path("steps")) {
                for (c in step.path("content")) {
                    if (c.path("type").asText() == "text") {
                        c.path("text").asText(null)?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
                    }
                }
            }
            return parts.joinToString(" ").trim()
        }

        fun extractAudio(json: JsonNode): ByteArray? {
            json.path("output_audio").path("data").asText(null)?.takeIf { it.isNotBlank() }?.let {
                return decodeB64(it)
            }
            for (step in json.path("steps")) {
                for (c in step.path("content")) {
                    if (c.path("type").asText() == "audio") {
                        c.path("data").asText(null)?.takeIf { it.isNotBlank() }?.let { return decodeB64(it) }
                    }
                }
            }
            return null
        }

        private fun decodeB64(raw: String): ByteArray = Base64.getDecoder().decode(raw)
    }
}
