package com.shifa.ai.application

import com.shifa.ai.domain.IntelligenceMemoryCandidate
import com.shifa.ai.domain.IntelligenceMemoryType

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.shifa.ai.infrastructure.LlmClient
import com.shifa.ai.domain.LlmPurpose
import com.shifa.ai.domain.LlmRequest
import com.shifa.ai.infrastructure.LlmUsageRecorder
import com.shifa.doctors.domain.DoctorProfile
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

/**
 * Turns a completed Intelligence turn into structured memory candidates.
 *
 * Runs after the reply has been streamed (never on the doctor's critical path) on a fixed
 * utility provider with no tools. Input is limited to the doctor's latest message, the spoken
 * reply, and tool names; patient context and tool payloads are never sent.
 */
@Component
class IntelligenceMemoryExtractor(
    private val client: LlmClient,
    private val registry: LlmProviderRegistry,
    private val usage: LlmUsageRecorder,
    private val props: AiRoutingProperties,
    private val memoryService: IntelligenceMemoryService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()

    /** Only utterances with preference-like wording trigger a model call. */
    private val triggers = listOf(
        "prefer", "always", "never", "from now on", "call it", "call them", "i like", "i don't like", "i dont like",
        "use ", "remember", "my usual", "shorthand", "in my clinic", "we call", "format", "24-hour", "12-hour",
        "предпочита", "всегда", "никогда", "запомни", "впредь", "называ", "в моей клинике", "формат",
        "doim", "hech qachon", "eslab qol", "yodda tut", "bundan keyin", "deb ata", "klinikamizda", "format",
    )

    fun shouldExtract(latestUserText: String): Boolean {
        val t = latestUserText.lowercase()
        return t.length >= 12 && triggers.any { t.contains(it) }
    }

    @Async("intelligenceMemoryExecutor")
    fun extractAsync(
        doctor: DoctorProfile,
        latestUserText: String,
        assistantReply: String,
        toolNames: List<String>,
        patientNamesInTurn: Collection<String>,
        requestId: String,
    ) {
        try {
            extract(doctor, latestUserText, assistantReply, toolNames, patientNamesInTurn, requestId)
        } catch (e: Exception) {
            log.warn("intelligence_memory_extract_failed doctorId={} reason={}", doctor.id, e.javaClass.simpleName)
        }
    }

    fun extract(
        doctor: DoctorProfile,
        latestUserText: String,
        assistantReply: String,
        toolNames: List<String>,
        patientNamesInTurn: Collection<String>,
        requestId: String,
    ): List<Long> {
        if (!props.memory.enabled) return emptyList()
        val provider = registry.get(props.memory.extractionProvider) ?: run {
            log.info("intelligence_memory_extract_skipped reason=no_provider id={}", props.memory.extractionProvider)
            return emptyList()
        }
        val request = LlmRequest(
            messages = listOf(
                mapOf("role" to "system", "content" to EXTRACTION_PROMPT),
                mapOf(
                    "role" to "user",
                    "content" to "Doctor said: ${latestUserText.take(600)}\nAssistant replied: ${assistantReply.take(300)}\nTools used: ${toolNames.joinToString(",")}",
                ),
            ),
            temperature = 0.0,
            maxTokens = 300,
            jsonObject = true,
        )
        val response = client.complete(request, provider)
        usage.record(
            LlmUsageRecorder.Record(
                requestId = requestId, doctorId = doctor.id, providerId = provider.id, model = response.model,
                language = null, category = null, purpose = LlmPurpose.MEMORY_EXTRACT,
                inputTokens = response.inputTokens, outputTokens = response.outputTokens,
                durationMs = response.durationMs, outcome = "SUCCESS", fallbackIndex = 0, toolNames = emptyList(),
            ),
        )
        val candidates = parseCandidates(response.content)
        return memoryService.persistCandidates(doctor, candidates, requestId, patientNamesInTurn)
    }

    fun parseCandidates(json: String): List<IntelligenceMemoryCandidate> {
        if (json.isBlank()) return emptyList()
        val root = try {
            mapper.readTree(json)
        } catch (_: Exception) {
            return emptyList()
        }
        val arr = when {
            root.isArray -> root
            root.path("memories").isArray -> root.path("memories")
            else -> return emptyList()
        }
        return arr.mapNotNull { n ->
            val type = runCatching { IntelligenceMemoryType.valueOf(n.path("type").asText("").trim().uppercase()) }.getOrNull()
                ?: return@mapNotNull null
            val content = n.path("content").asText("").trim()
            if (content.isEmpty()) return@mapNotNull null
            IntelligenceMemoryCandidate(
                type = type,
                content = content,
                contentKey = n.path("key").asText(null)?.takeIf { it.isNotBlank() },
                confidence = n.path("confidence").asDouble(0.0),
                temporary = n.path("temporary").asBoolean(false),
            )
        }
    }

    companion object {
        val EXTRACTION_PROMPT = """
You extract durable operational preferences a clinic doctor states to their assistant.
Return ONLY JSON: {"memories":[{"type":"DOCTOR_PREFERENCE|WORKFLOW_PREFERENCE|TERMINOLOGY|TEMPORARY_CONTEXT","content":"...","key":"snake_case_topic","confidence":0.0-1.0,"temporary":false}]}
Rules:
- Only explicit, reusable preferences about HOW the assistant should behave or WHAT words mean in this clinic
  (time format, how to present schedules, naming conventions, workflow habits).
- NEVER include patient names, identifiers, phone numbers, diagnoses, medications, or anything about a specific patient.
- "For the next hour / today only / right now" style requests are temporary:true with type TEMPORARY_CONTEXT.
- key: short stable topic such as time_format, schedule_presentation, term_<word>. Same topic -> same key.
- If nothing qualifies, return {"memories":[]}.
- Max 3 items. Content under 200 characters, written in English as a plain statement.
        """.trimIndent()
    }
}
