package com.shifa.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.shifa.ai.MedicalBiasPrompt
import com.shifa.config.OpenAiProperties
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Path
import java.time.Duration

/**
 * Speech-to-text via OpenAI Audio API ([OpenAiProperties.transcriptionModel]),
 * default **gpt-4o-transcribe** (falls back to whisper-1 if overridden).
 */
@Service
class TranscriptionService(
    private val openAiProps: OpenAiProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()

    private val httpClientTranscribe = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(30))
        .writeTimeout(Duration.ofSeconds(120))
        .readTimeout(Duration.ofSeconds(120))
        .build()

    /** Smaller timeouts for typo-cleanup completions. */
    private val httpCleanup = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .writeTimeout(Duration.ofSeconds(30))
        .readTimeout(Duration.ofSeconds(90))
        .build()

    data class TranscriptionResult(
        val transcript: String,
        val language: String? = null,
        val durationSeconds: Double? = null
    )

    /**
     * Transcribe audio file to text using OpenAI Audio Transcriptions API.
     * Supports: mp3, mp4, mpeg, mpga, m4a, wav, webm
     *
     * @param purpose [TranscriptionPurpose.SCRIBE_PIPELINE] applies optional medical typo cleanup by default;
     *        [TranscriptionPurpose.VOICE_UPLOAD] only cleans up when configured.
     */
    fun transcribe(
        audioPath: Path,
        languageHint: String? = null,
        purpose: TranscriptionPurpose = TranscriptionPurpose.VOICE_UPLOAD
    ): TranscriptionResult {
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

        val modelId = openAiProps.transcriptionModel.trim().ifBlank { "gpt-4o-transcribe" }
        val requestBodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("audio/*".toMediaType()))
            .addFormDataPart("model", modelId)
            .addFormDataPart("response_format", "json")

        val useGptTranscribeChunking =
            modelId.contains("gpt-4o", ignoreCase = true) &&
                modelId.contains("transcribe", ignoreCase = true)
        if (useGptTranscribeChunking) {
            requestBodyBuilder.addFormDataPart("chunking_strategy", "auto")
        }

        val isWhisperModel = modelId.startsWith("whisper", ignoreCase = true)
        if (isWhisperModel) {
            requestBodyBuilder.addFormDataPart("temperature", "0")
        }

        normalizedLanguageHint?.let { lang ->
            val sendLanguageParam =
                isWhisperModel || openAiProps.transcriptionSendLanguageParam
            if (sendLanguageParam) {
                requestBodyBuilder.addFormDataPart("language", lang)
            }
        }

        val biasPrompt = capTranscriptionPrompt(MedicalBiasPrompt.build(normalizedLanguageHint))
        requestBodyBuilder.addFormDataPart("prompt", biasPrompt)

        val requestBody = requestBodyBuilder.build()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer ${openAiProps.apiKey}")
            .addHeader("OpenAI-Project", openAiProps.projectId)
            .post(requestBody)
            .build()

        log.info(
            "Transcribing audio: {} ({} bytes), model={}, languageHint={}, purpose={}",
            file.name,
            file.length(),
            modelId,
            normalizedLanguageHint ?: "auto",
            purpose
        )

        httpClientTranscribe.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: ""
                log.error("OpenAI transcription API error: {} - {}", response.code, body)
                throw RuntimeException("Transcription failed: ${response.code} - $body")
            }

            val body = response.body?.string() ?: "{}"
            val json = mapper.readValue<Map<String, Any?>>(body)
            val text = (json["text"] as? String)?.trim() ?: ""

            log.info("Transcription complete (raw): {} chars", text.length)

            val rawOutcome = TranscriptionResult(
                transcript = text.ifBlank { "(No speech detected)" },
                language = json["language"] as? String
            )

            return applyMedicalCleanupIfEnabled(rawOutcome, normalizedLanguageHint, purpose)
        }
    }

    private fun capTranscriptionPrompt(prompt: String): String =
        prompt.take(MAX_TRANSCRIPTION_PROMPT_CHARS)

    private fun applyMedicalCleanupIfEnabled(
        outcome: TranscriptionResult,
        languageHint: String?,
        purpose: TranscriptionPurpose
    ): TranscriptionResult {
        if (!openAiProps.transcriptionMedicalCleanupEnabled) return outcome
        val transcript = outcome.transcript
        if (transcript.isBlank() || transcript == "(No speech detected)") return outcome

        val words = transcript.countWordsRough()
        if (words < openAiProps.transcriptionMedicalCleanupMinWords) return outcome

        val forVoice = purpose == TranscriptionPurpose.VOICE_UPLOAD &&
            openAiProps.transcriptionMedicalCleanupVoiceUploads
        val forScribe = purpose == TranscriptionPurpose.SCRIBE_PIPELINE
        if (!forVoice && !forScribe) return outcome

        return try {
            val cleaned = cleanupMedicalTerms(transcript, languageHint)
            if (cleaned.isNotBlank() && cleaned != transcript) {
                log.info(
                    "STT cleanup applied: chars {} -> {}; rawPreview=[{}]; cleanedPreview=[{}]",
                    transcript.length,
                    cleaned.length,
                    transcript.previewForLog(LOG_PREVIEW_CHARS),
                    cleaned.previewForLog(LOG_PREVIEW_CHARS)
                )
            } else {
                log.debug("STT cleanup unchanged ({} chars)", transcript.length)
            }
            outcome.copy(transcript = cleaned.ifBlank { transcript })
        } catch (e: Exception) {
            log.warn("STT cleanup skipped after error: {}", e.message)
            outcome
        }
    }

    private fun cleanupMedicalTerms(transcript: String, languageIsoOrHint: String?): String {
        val langLine = languageIsoOrHint?.let {
            "The dominant spoken language bias is ISO 639-1: $it. Text may mix Uzbek (Latin/Cyrillic), Russian, and English."
        } ?: "Text may mix Uzbek (Latin/Cyrillic), Russian, and English clinical terms."

        val systemPrompt = """
You correct ONLY obvious misspellings of medical terms, drug names (Latin/international and local brand names),
specialty titles, ICD-style codes (e.g. ICD-10, MKB), and short procedural terms in clinical speech transcripts.

Rules:
- Do NOT change meaning, diagnoses, narratives, doses, numeric values (including blood pressure, lab values), dates, phone numbers, addresses, proper names.
- Do NOT paraphrase, summarize, reorder, add words, omit words, translate, or "improve style".
- Preserve code-switching: do not unify languages.
- When unsure, return the transcript exactly unchanged.

$langLine

Output format: Reply with ONLY the corrected transcript text, no preamble, quotes, markdown, or explanations.
If nothing needs fixing, output the transcript byte-for-byte the same.

""".trimIndent()

        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to openAiProps.transcriptionMedicalCleanupModel,
                "stream" to false,
                "temperature" to 0.0,
                "max_tokens" to cleanupMaxTokens(transcript.length),
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to transcript)
                )
            )
        )

        val httpRequest = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${openAiProps.apiKey}")
            .addHeader("OpenAI-Project", openAiProps.projectId)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        httpCleanup.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: ""
                log.warn("Medical cleanup API error {}: {}", response.code, err.take(512))
                throw RuntimeException("cleanup ${response.code}")
            }
            val body = response.body?.string() ?: "{}"
            val json: JsonNode = mapper.readTree(body)
            val content = json.path("choices").path(0).path("message").path("content").asText("").trim()
            return content
        }
    }

    private fun cleanupMaxTokens(inputChars: Int): Int =
        (inputChars / 3 + CLEANUP_EXTRA_TOKENS).coerceIn(256, CLEANUP_OUTPUT_CAP_TOKENS)

    private fun String.countWordsRough(): Int =
        trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    private fun String.previewForLog(maxChars: Int): String {
        val t = trim()
        if (t.length <= maxChars) return t
        return t.take(maxChars) + "..."
    }

    companion object {
        private const val MAX_TRANSCRIPTION_PROMPT_CHARS = 4000
        private const val LOG_PREVIEW_CHARS = 280
        private const val CLEANUP_EXTRA_TOKENS = 320
        private const val CLEANUP_OUTPUT_CAP_TOKENS = 6000
    }
}
