package com.shifa.ai.application


import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.shifa.ai.domain.OutputLanguage
import com.shifa.ai.domain.TranscriptPromptLeakStripper
import com.shifa.shared.config.OpenAiProperties
import com.shifa.appointments.domain.PatientVisitFact
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Extracts medications, stated diagnoses, and therapy steps from a spoken visit transcript.
 * Every kept item must cite a quote from the transcript; a reflection pass drops the rest.
 */
@Service
class VisitFactExtractionService(
    private val openAiProps: OpenAiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()
    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(30))
        .writeTimeout(Duration.ofSeconds(60))
        .readTimeout(Duration.ofSeconds(90))
        .build()

    data class ExtractedFact(
        val factType: PatientVisitFact.FactType,
        val text: String,
        val quote: String?,
        val scheduleHint: String?,
        val reflectionKept: Boolean,
        val dropReason: String?,
    )

    data class ExtractionResult(
        val kept: List<ExtractedFact>,
        val dropped: List<ExtractedFact>,
    )

    fun extract(transcript: String, language: OutputLanguage = OutputLanguage.UZ): ExtractionResult {
        val spoken = TranscriptPromptLeakStripper.strip(transcript)
        if (spoken.isBlank() || spoken == "(No speech detected)") {
            return ExtractionResult(emptyList(), emptyList())
        }

        val candidates = runExtraction(spoken, language)
        val reflected = reflect(spoken, candidates, language)
        return ExtractionResult(
            kept = reflected.filter { it.reflectionKept },
            dropped = reflected.filter { !it.reflectionKept },
        )
    }

    private fun runExtraction(transcript: String, language: OutputLanguage): List<ExtractedFact> {
        val langLine = when (language) {
            OutputLanguage.EN -> "Write text fields in English."
            OutputLanguage.RU -> "Write text fields in Russian."
            OutputLanguage.UZ -> "Write text fields in Uzbek (Latin or Cyrillic as spoken)."
        }
        val system = """
You extract ONLY what the doctor clearly ordered or stated in a live visit transcript.

$langLine

GOLDEN RULE: Never invent medications, diagnoses, symptoms, or therapy. If it was not spoken, omit it.

Return ONLY valid JSON:
{
  "items": [
    {
      "factType": "MEDICATION" | "DIAGNOSIS" | "THERAPY" | "SYMPTOM",
      "text": "short clinical phrase",
      "quote": "exact or near-exact words from the transcript",
      "scheduleHint": "optional dose schedule if spoken, else null"
    }
  ]
}

Rules:
- MEDICATION: drugs the doctor told the patient to take.
- DIAGNOSIS: diagnoses the doctor stated out loud (not guesses).
- THERAPY: therapy / follow-up / non-drug plan steps the doctor stated.
- SYMPTOM: symptoms the patient reported that the doctor acknowledged.
- quote MUST be taken from the transcript. If you cannot quote it, omit the item.
- Prefer fewer accurate items over many weak ones.
""".trimIndent()

        val raw = completeJson(system, "Transcript:\n\n$transcript")
        return parseItems(raw)
    }

    private fun reflect(
        transcript: String,
        candidates: List<ExtractedFact>,
        language: OutputLanguage,
    ): List<ExtractedFact> {
        if (candidates.isEmpty()) return emptyList()
        val lower = transcript.lowercase()
        // Deterministic first pass: quote must appear (fuzzy substring) in transcript.
        val preChecked = candidates.map { item ->
            val quote = item.quote?.trim().orEmpty()
            if (quote.length < 4) {
                item.copy(reflectionKept = false, dropReason = "Missing transcript quote")
            } else if (!lower.contains(quote.lowercase().take(40)) &&
                quote.split(Regex("\\s+")).count { it.length > 3 && lower.contains(it.lowercase()) } < 2
            ) {
                item.copy(reflectionKept = false, dropReason = "Quote not found in transcript")
            } else {
                item.copy(reflectionKept = true, dropReason = null)
            }
        }

        val stillKept = preChecked.filter { it.reflectionKept }
        if (stillKept.isEmpty()) return preChecked

        val system = """
You are a clinical safety checker. For each candidate fact, decide KEEP or DROP.
KEEP only if the quote truly supports the fact and the doctor/patient actually said it.
DROP if the fact invents details, dosages not spoken, or the quote does not support it.

Return ONLY JSON:
{ "results": [ { "index": 0, "keep": true, "reason": "short" } ] }

Language for reason: ${language.name}.
""".trimIndent()

        val payloadItems = stillKept.mapIndexed { idx, f ->
            mapOf(
                "index" to idx,
                "factType" to f.factType.name,
                "text" to f.text,
                "quote" to f.quote,
                "scheduleHint" to f.scheduleHint,
            )
        }
        val raw = completeJson(
            system,
            "Transcript:\n$transcript\n\nCandidates:\n${mapper.writeValueAsString(payloadItems)}"
        )
        val decisions = mutableMapOf<Int, Pair<Boolean, String?>>()
        try {
            val root = mapper.readTree(raw)
            val arr = root.path("results")
            if (arr.isArray) {
                for (n in arr) {
                    val index = n.path("index").asInt(-1)
                    if (index < 0) continue
                    decisions[index] = n.path("keep").asBoolean(false) to n.path("reason").asText(null)
                }
            }
        } catch (e: Exception) {
            log.warn("Visit fact reflection parse failed: {}", e.message)
        }

        val remapped = stillKept.mapIndexed { idx, f ->
            val decision = decisions[idx]
            if (decision == null) {
                f
            } else if (decision.first) {
                f.copy(reflectionKept = true, dropReason = null)
            } else {
                f.copy(reflectionKept = false, dropReason = decision.second ?: "Dropped by reflection")
            }
        }
        val droppedEarly = preChecked.filter { !it.reflectionKept }
        return droppedEarly + remapped
    }

    private fun parseItems(raw: String): List<ExtractedFact> {
        return try {
            val root = mapper.readTree(extractJsonObject(raw))
            val items = root.path("items")
            if (!items.isArray) return emptyList()
            items.mapNotNull { n -> toFact(n) }
        } catch (e: Exception) {
            log.warn("Visit fact extraction parse failed: {}", e.message)
            emptyList()
        }
    }

    private fun toFact(n: JsonNode): ExtractedFact? {
        val typeRaw = n.path("factType").asText("").uppercase()
        val type = try {
            PatientVisitFact.FactType.valueOf(typeRaw)
        } catch (_: Exception) {
            return null
        }
        val text = n.path("text").asText("").trim()
        if (text.isBlank()) return null
        val quote = n.path("quote").asText(null)?.trim()?.takeIf { it.isNotBlank() }
        val schedule = n.path("scheduleHint").asText(null)?.trim()?.takeIf { it.isNotBlank() }
        return ExtractedFact(
            factType = type,
            text = text,
            quote = quote,
            scheduleHint = schedule,
            reflectionKept = true,
            dropReason = null,
        )
    }

    private fun completeJson(system: String, user: String): String {
        if (openAiProps.apiKey.isBlank()) return """{"items":[]}"""
        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to openAiProps.model,
                "stream" to false,
                "temperature" to 0.0,
                "max_tokens" to 1200,
                "response_format" to mapOf("type" to "json_object"),
                "messages" to listOf(
                    mapOf("role" to "system", "content" to system),
                    mapOf("role" to "user", "content" to user),
                ),
            )
        )
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${openAiProps.apiKey}")
            .addHeader("OpenAI-Project", openAiProps.projectId)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                log.warn("Visit fact extraction HTTP {}", response.code)
                return """{"items":[]}"""
            }
            val body = response.body?.string().orEmpty()
            val json = mapper.readTree(body)
            return json.path("choices").path(0).path("message").path("content").asText("{}")
        }
    }

    private fun extractJsonObject(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) return raw.substring(start, end + 1)
        return raw
    }
}
