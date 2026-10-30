package com.shifa.ai.application


import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import com.shifa.ai.domain.MedicalBiasPrompt
import com.shifa.ai.domain.TranscriptPromptLeakStripper
import com.shifa.ai.infrastructure.GeminiSpeechClient
import com.shifa.shared.config.OpenAiProperties
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
 * Speech-to-text. Intelligence / voice uploads in Uzbek (or auto-detect) use Gemini
 * [gemini-3.5-transcribe] when a Gemini key is configured; otherwise OpenAI
 * [OpenAiProperties.transcriptionModel]. Scribe stays on OpenAI.
 */
@Service
class TranscriptionService(
    private val openAiProps: OpenAiProperties,
    private val geminiSpeech: GeminiSpeechClient? = null,
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

        if (shouldUseGeminiStt(normalizedLanguageHint, purpose)) {
            try {
                val gemini = geminiSpeech!!.transcribe(
                    file = file,
                    mimeType = mimeTypeOf(file),
                    languageBcp47 = if (normalizedLanguageHint == "uz") "uz-UZ" else null,
                )
                val stripped = TranscriptPromptLeakStripper.strip(gemini.transcript).ifBlank { gemini.transcript }
                log.info("Transcription complete (gemini): {} chars", stripped.length)
                return TranscriptionResult(
                    transcript = stripped.ifBlank { "(No speech detected)" },
                    language = gemini.language ?: normalizedLanguageHint,
                )
            } catch (e: Exception) {
                log.warn("gemini_stt_fallback_openai reason={}", e.javaClass.simpleName)
            }
        }

        val modelId = openAiProps.transcriptionModel.trim().ifBlank { "gpt-4o-transcribe" }
        val isWhisperModel = modelId.startsWith("whisper", ignoreCase = true)
        // Auto-detect: verbose_json returns detected language (whisper / gpt-4o-transcribe).
        val responseFormat =
            if (normalizedLanguageHint == null && isWhisperModel) "verbose_json" else "json"

        val requestBodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("audio/*".toMediaType()))
            .addFormDataPart("model", modelId)
            .addFormDataPart("response_format", responseFormat)

        val useGptTranscribeChunking =
            modelId.contains("gpt-4o", ignoreCase = true) &&
                modelId.contains("transcribe", ignoreCase = true)
        if (useGptTranscribeChunking) {
            requestBodyBuilder.addFormDataPart("chunking_strategy", "auto")
        }

        if (isWhisperModel) {
            requestBodyBuilder.addFormDataPart("temperature", "0")
        }

        val apiLanguageParam = openAiLanguageParam(normalizedLanguageHint, modelId)
        apiLanguageParam?.let { lang ->
            requestBodyBuilder.addFormDataPart("language", lang)
        }

        // When auto-detecting, avoid a strong Uzbek bias prompt that pulls English into Uzbek.
        val biasPrompt = if (normalizedLanguageHint == null) {
            capTranscriptionPrompt(
                "Transcribe exactly what was spoken. The speaker may use English, Russian, or Uzbek. Do not translate."
            )
        } else {
            capTranscriptionPrompt(MedicalBiasPrompt.build(normalizedLanguageHint))
        }
        requestBodyBuilder.addFormDataPart("prompt", biasPrompt)

        val requestBody = requestBodyBuilder.build()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer ${openAiProps.apiKey}")
            .addHeader("OpenAI-Project", openAiProps.projectId)
            .post(requestBody)
            .build()

        log.info(
            "Transcribing audio: {} ({} bytes), model={}, languageHint={}, apiLanguage={}, purpose={}",
            file.name,
            file.length(),
            modelId,
            normalizedLanguageHint ?: "auto",
            apiLanguageParam ?: "prompt-only",
            purpose
        )

        return executeTranscription(request, normalizedLanguageHint, purpose) {
            if (apiLanguageParam == null) return@executeTranscription null
            log.info("Retrying transcription without language= param (hint={})", normalizedLanguageHint)
            val retryBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.asRequestBody("audio/*".toMediaType()))
                .addFormDataPart("model", modelId)
                .addFormDataPart("response_format", responseFormat)
                .also { builder ->
                    if (useGptTranscribeChunking) {
                        builder.addFormDataPart("chunking_strategy", "auto")
                    }
                    if (isWhisperModel) {
                        builder.addFormDataPart("temperature", "0")
                    }
                    builder.addFormDataPart("prompt", biasPrompt)
                }
                .build()
            Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer ${openAiProps.apiKey}")
                .addHeader("OpenAI-Project", openAiProps.projectId)
                .post(retryBody)
                .build()
        }
    }

    /**
     * Maps UI/profile hints to OpenAI `language=` when the model accepts the code.
     * Uzbek (`uz`) and other unlisted codes rely on [MedicalBiasPrompt] only — gpt-4o-transcribe
     * may reject `language=uz` (see README: OPENAI_TRANSCRIPTION_SEND_LANG).
     */
    /** Intelligence auto-detect (no hint) and explicit Uzbek use Gemini; en/ru and scribe stay OpenAI. */
    internal fun shouldUseGeminiStt(normalizedHint: String?, purpose: TranscriptionPurpose): Boolean {
        if (geminiSpeech?.isAvailable() != true) return false
        if (purpose != TranscriptionPurpose.VOICE_UPLOAD) return false
        return normalizedHint == null || normalizedHint == "uz"
    }

    private fun mimeTypeOf(file: File): String {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".wav") -> "audio/wav"
            name.endsWith(".mp3") -> "audio/mp3"
            name.endsWith(".webm") -> "audio/webm"
            name.endsWith(".ogg") -> "audio/ogg"
            name.endsWith(".mp4") -> "audio/mp4"
            else -> "audio/mp4"
        }
    }

    internal fun openAiLanguageParam(normalizedHint: String?, modelId: String): String? {
        if (normalizedHint.isNullOrBlank()) return null
        val isWhisperModel = modelId.startsWith("whisper", ignoreCase = true)
        if (isWhisperModel) return normalizedHint
        if (!openAiProps.transcriptionSendLanguageParam) return null
        return when (normalizedHint) {
            "en", "ru" -> normalizedHint
            else -> null
        }
    }

    private fun executeTranscription(
        request: Request,
        normalizedLanguageHint: String?,
        purpose: TranscriptionPurpose,
        buildRetryWithoutLanguage: () -> Request?
    ): TranscriptionResult {
        httpClientTranscribe.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: ""
                log.error("OpenAI transcription API error: {}", response.code)
                log.debug("OpenAI transcription API error body: {}", body)
                val retry = buildRetryWithoutLanguage()
                if (retry != null && shouldRetryWithoutLanguage(response.code, body)) {
                    return executeTranscription(retry, normalizedLanguageHint, purpose) { null }
                }
                throw TranscriptionFailedException(response.code, body)
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
                .let { cleaned ->
                    val stripped = TranscriptPromptLeakStripper.strip(cleaned.transcript)
                    if (stripped != cleaned.transcript) {
                        log.info(
                            "STT prompt-leak strip: chars {} -> {}",
                            cleaned.transcript.length,
                            stripped.length,
                        )
                    }
                    cleaned.copy(transcript = stripped.ifBlank { cleaned.transcript })
                }
        }
    }

    private fun shouldRetryWithoutLanguage(httpCode: Int, body: String): Boolean {
        if (httpCode !in 400..499) return false
        val lower = body.lowercase()
        return lower.contains("language") ||
            lower.contains("invalid_request_error") ||
            lower.contains("unsupported_format")
    }

    class TranscriptionFailedException(val httpCode: Int, val responseBody: String) :
        RuntimeException("Transcription failed: $httpCode - $responseBody")

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
            val accepted = TranscriptIntegrity.acceptCleanup(transcript, cleaned)
            if (accepted != transcript) {
                log.info(
                    "doctor_stt outcome=CLEANUP_APPLIED chars {} -> {}",
                    transcript.length,
                    accepted.length,
                )
            } else if (cleaned.isNotBlank() && cleaned != transcript) {
                log.info(
                    "doctor_stt outcome=CLEANUP_REJECTED_SHORT originalChars={} cleanedChars={}",
                    transcript.length,
                    cleaned.length,
                )
            } else {
                log.debug("STT cleanup unchanged ({} chars)", transcript.length)
            }
            outcome.copy(transcript = accepted)
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
                log.warn("Medical cleanup API error {}", response.code)
                log.debug("Medical cleanup API error body: {}", err.take(512))
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

    companion object {
        private const val MAX_TRANSCRIPTION_PROMPT_CHARS = 4000
        private const val CLEANUP_EXTRA_TOKENS = 320
        private const val CLEANUP_OUTPUT_CAP_TOKENS = 6000
    }
}
