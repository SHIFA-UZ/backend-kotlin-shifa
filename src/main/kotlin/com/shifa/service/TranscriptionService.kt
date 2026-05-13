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
 * Speech-to-text via OpenAI Audio API ([OpenAiProperties.transcriptionModel]).
 * Default **whisper-1** for complete transcripts; **gpt-4o-transcribe** is optional but may truncate
 * mid-utterance or near pauses (see OpenAI community reports).
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
     * Transcribe audio file to text using OpenAI Audio Transcriptions API.
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

        val modelId = openAiProps.transcriptionModel.trim().ifBlank { "whisper-1" }
        val requestBodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("audio/*".toMediaType()))
            .addFormDataPart("model", modelId)
            // gpt-4o-transcribe supports json | text; verbose_json is for whisper-1.
            .addFormDataPart("response_format", "json")

        val isWhisperModel = modelId.startsWith("whisper", ignoreCase = true)
        if (isWhisperModel) {
            requestBodyBuilder.addFormDataPart("temperature", "0")
        }

        normalizedLanguageHint?.let { lang ->
            // gpt-4o-transcribe currently rejects some ISO codes (e.g. "uz").
            // Keep explicit language only for Whisper; for GPT models rely on prompt bias.
            if (isWhisperModel) {
                requestBodyBuilder.addFormDataPart("language", lang)
            }
            // Latin-Uzbek decoding hint reduces Azerbaijani/Turkish drift on short clips.
            val biasPrompt = when (lang) {
                "uz" -> "O'zbekcha tibbiy suhbat: shifokor, qabul, simptom, dori, kasalxona, sog'liqni saqlash."
                "ru" -> "Медицинский разговор на русском языке: врач, приём, симптомы, лекарства, больница."
                "en" -> "English medical conversation: doctor, appointment, symptoms, medication, clinic."
                else -> null
            }
            biasPrompt?.let { requestBodyBuilder.addFormDataPart("prompt", it) }
        }

        val requestBody = requestBodyBuilder.build()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer ${openAiProps.apiKey}")
            .addHeader("OpenAI-Project", openAiProps.projectId)
            .post(requestBody)
            .build()

        log.info(
            "Transcribing audio: {} ({} bytes), model={}, languageHint={}",
            file.name,
            file.length(),
            modelId,
            normalizedLanguageHint ?: "auto"
        )

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: ""
                log.error("OpenAI transcription API error: {} - {}", response.code, body)
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
