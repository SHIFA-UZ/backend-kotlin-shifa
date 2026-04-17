package com.shifa.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.shifa.config.OpenAiProperties
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Path
import java.time.Duration

/**
 * Speech-to-text via OpenAI Whisper API.
 */
@Service
class TranscriptionService(
    private val openAiProps: OpenAiProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()

    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(30))
        .writeTimeout(Duration.ofSeconds(120))
        .readTimeout(Duration.ofSeconds(120))
        .build()

    data class TranscriptionResult(
        val transcript: String,
        val language: String? = null,
        val durationSeconds: Double? = null
    )

    /**
     * Transcribe audio file to text using Whisper API.
     * Supports: mp3, mp4, mpeg, mpga, m4a, wav, webm
     */
    fun transcribe(audioPath: Path, languageHint: String? = null): TranscriptionResult {
        val file = audioPath.toFile()
        if (!file.exists()) {
            throw IllegalArgumentException("Audio file not found: $audioPath")
        }
        if (file.length() > 25 * 1024 * 1024) {
            throw IllegalArgumentException("Audio file exceeds Whisper 25 MB limit")
        }

        val normalizedLanguageHint = when (languageHint?.trim()?.lowercase()) {
            "uz", "uzbek", "uz-uz", "uz_uz" -> "uz"
            "ru", "rus", "russian", "ru-ru", "ru_ru" -> "ru"
            "en", "eng", "english", "en-us", "en_us", "en-gb", "en_gb" -> "en"
            else -> null
        }

        val requestBodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("audio/*".toMediaType()))
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("response_format", "json")

        // Strong hint to Whisper: use the active app language when provided.
        normalizedLanguageHint?.let { requestBodyBuilder.addFormDataPart("language", it) }

        val requestBody = requestBodyBuilder.build()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer ${openAiProps.apiKey}")
            .addHeader("OpenAI-Project", openAiProps.projectId)
            .post(requestBody)
            .build()

        log.info(
            "Transcribing audio: {} ({} bytes), languageHint={}",
            file.name,
            file.length(),
            normalizedLanguageHint ?: "auto"
        )

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: ""
                log.error("Whisper API error: {} - {}", response.code, body)
                throw RuntimeException("Transcription failed: ${response.code} - $body")
            }

            val body = response.body?.string() ?: "{}"
            val json = mapper.readValue<Map<String, Any?>>(body)
            val text = (json["text"] as? String)?.trim() ?: ""

            log.info("Transcription complete: {} chars", text.length)
            return TranscriptionResult(
                transcript = text.ifBlank { "(No speech detected)" },
                language = json["language"] as? String
            )
        }
    }
}
