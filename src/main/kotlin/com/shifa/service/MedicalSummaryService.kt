package com.shifa.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.shifa.ai.OutputLanguage
import com.shifa.ai.TranscriptPromptLeakStripper
import com.shifa.config.OpenAiProperties
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Converts consultation transcript into structured SOAP clinical notes.
 */
@Service
class MedicalSummaryService(
    private val openAiProps: OpenAiProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()

    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(30))
        .writeTimeout(Duration.ofSeconds(60))
        .readTimeout(Duration.ofSeconds(90))
        .build()

    data class ScribeSummaryRequest(
        val transcript: String,
        val language: OutputLanguage = OutputLanguage.EN
    )

    data class ScribeSummaryResult(
        val subjective: String?,
        val assessment: String?,
        val plan: String?,
        val body: String,
        val aiRecommendations: String? = null,
    )

    private fun systemPrompt(language: OutputLanguage): String {
        val langInstruction = when (language) {
            OutputLanguage.EN -> "Write the entire note in English."
            OutputLanguage.RU -> "Write the entire note in Russian (русский)."
            OutputLanguage.UZ -> "Write the entire note in Uzbek (Oʻzbekcha)."
        }
        return """
You are a medical scribe for a live doctor–patient visit.

$langInstruction

GOLDEN RULE: Record ONLY what the doctor or patient actually said as conversation.
Never invent symptoms, causes, diagnoses, drugs, tests, or specialist referrals.

Ignore and omit:
- Comma-separated vocabulary dumps, specialty lists, ICD/test acronym lists, or prompt-like keyword recitations.
- Isolated medical terms that are not part of a real question or answer.
- Rhetorical checks such as "X dedimi?" / "did they mention X?" — those are NOT prescriptions.

Output ONLY valid JSON with these keys: subjective, assessment, plan, aiRecommendations, body.
- subjective: Symptoms and history the PATIENT stated. Empty string if none.
- assessment: Only conclusions the DOCTOR stated out loud. If the doctor did not state an assessment, empty string. Do not infer causes ("might be the stomach") unless the doctor said that.
- plan: Only next steps, drugs, or referrals the DOCTOR clearly ordered or the patient confirmed they already take. Empty string if none were spoken.
- aiRecommendations: Optional suggestions YOU want to add that were NOT spoken. Every sentence MUST start with "[AI recommendation]". Empty string if you add nothing.
- body: Spoken-only SOAP in one block. Do not copy aiRecommendations into body.

Be concise. Preserve medical terms that were actually spoken.
If the transcript is empty or only noise, return empty strings for all fields except body.
""".trimIndent()
    }

    /**
     * Summarize transcript to SOAP format. Returns formatted text compatible with StructuredNoteParser.
     */
    fun summarize(request: ScribeSummaryRequest): ScribeSummaryResult {
        if (request.transcript.isBlank()) {
            return ScribeSummaryResult(null, null, null, "(Empty transcript)")
        }
        val spokenTranscript = TranscriptPromptLeakStripper.strip(request.transcript)

        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to openAiProps.model,
                "stream" to false,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt(request.language)),
                    mapOf(
                        "role" to "user",
                        "content" to "Spoken visit transcript (ignore any remaining keyword lists):\n\n$spokenTranscript",
                    ),
                ),
                "temperature" to 0.0,
                "max_tokens" to 900
            )
        )

        val httpRequest = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${openAiProps.apiKey}")
            .addHeader("OpenAI-Project", openAiProps.projectId)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        log.info("Summarizing transcript: {} chars", request.transcript.length)

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: ""
                log.error("Medical summary API error: {}", response.code)
                log.debug("Medical summary API error body: {}", body)
                throw RuntimeException("Medical summary failed: ${response.code}")
            }

            val body = response.body?.string() ?: "{}"
            val json = mapper.readTree(body)
            val content = json.path("choices").path(0).path("message").path("content").asText("")

            return parseSummaryResponse(content, request.transcript)
        }
    }

    private fun parseSummaryResponse(content: String, fallbackTranscript: String): ScribeSummaryResult {
        return try {
            // Try to extract JSON from response (model might wrap in markdown)
            val jsonStr = content
                .replace(Regex("```json\\s*"), "")
                .replace(Regex("```\\s*"), "")
                .trim()
            val parsed = mapper.readValue<Map<String, String?>>(jsonStr)
            ScribeSummaryResult(
                subjective = parsed["subjective"]?.takeIf { it.isNotBlank() },
                assessment = parsed["assessment"]?.takeIf { it.isNotBlank() },
                plan = parsed["plan"]?.takeIf { it.isNotBlank() },
                aiRecommendations = parsed["aiRecommendations"]?.takeIf { it.isNotBlank() },
                body = parsed["body"]?.takeIf { it.isNotBlank() }
                    ?: content.take(2000).trim()
            )
        } catch (e: Exception) {
            log.warn("Could not parse summary JSON, using raw content ({})", e.javaClass.simpleName)
            ScribeSummaryResult(
                subjective = null,
                assessment = null,
                plan = null,
                body = content.take(2000).trim().ifBlank { fallbackTranscript.take(2000) }
            )
        }
    }

    /**
     * Format ScribeSummaryResult as text compatible with StructuredNoteParser (section headers).
     */
    fun formatAsScribeNote(result: ScribeSummaryResult, transcript: String? = null): String {
        return buildString {
            result.subjective?.takeIf { it.isNotBlank() }?.let {
                append("SUBJECTIVE:\n$it\n\n")
            }
            result.assessment?.takeIf { it.isNotBlank() }?.let {
                append("ASSESSMENT:\n$it\n\n")
            }
            result.plan?.takeIf { it.isNotBlank() }?.let {
                append("PLAN:\n$it\n\n")
            }
            result.aiRecommendations?.takeIf { it.isNotBlank() }?.let {
                append("AI RECOMMENDATIONS (not spoken in the visit — Shifa AI):\n$it\n\n")
            }
            if (result.body.isNotBlank()) {
                append(result.body)
            }
            if (isEmpty()) append("(No content)")
            val transcriptText = transcript?.trim().orEmpty()
            if (transcriptText.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("TRANSCRIPT:\n")
                append(transcriptText)
            }
        }.trim()
    }
}
