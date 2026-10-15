package com.shifa.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.shifa.ai.MedicalPromptBuilder
import com.shifa.ai.OutputLanguage
import com.shifa.ai.PatientAiContext
import com.shifa.ai.RedFlagEngine
import com.shifa.config.OpenAiProperties
import com.shifa.domain.DoctorProfile
import com.shifa.web.AiStreamException
import com.shifa.web.dto.AiMessageDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Spoken morning / Intelligence session: tool-augmented assistant.
 * Emits [MorningStreamEvent.Tool] for card UI, then short spoken [MorningStreamEvent.Token]s.
 */
@Service
class DoctorMorningAiService(
    private val props: OpenAiProperties,
    private val morningTools: DoctorMorningToolService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()
    private val rateLimiter = SimpleRateLimiter(props.maxRequestsPerMinute)

    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .writeTimeout(Duration.ofSeconds(15))
        .readTimeout(Duration.ZERO)
        .build()

    private val toolClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .writeTimeout(Duration.ofSeconds(15))
        .readTimeout(Duration.ofSeconds(45))
        .build()

    fun streamMorningAssistant(
        doctor: DoctorProfile,
        patientContext: PatientAiContext?,
        messages: List<AiMessageDto>,
        language: OutputLanguage,
        selectedPatientId: Long?,
    ): Flow<MorningStreamEvent> = flow {
        val combined = messages.joinToString(" ") { it.content }
        if (RedFlagEngine.analyze(combined).hasEmergency) {
            throw AiStreamException(
                code = "SAFETY_BLOCK",
                message = "This may represent a medical emergency. Please seek immediate professional medical care.",
            )
        }
        if (!rateLimiter.tryAcquire()) {
            throw AiStreamException(code = "RATE_LIMIT", message = "AI rate limit exceeded. Please try again later.")
        }

        val systemMessages = mutableListOf(
            mapOf("role" to "system", "content" to morningSystemPrompt(doctor, language)),
        )
        patientContext?.let {
            systemMessages += mapOf("role" to "system", "content" to MedicalPromptBuilder.patientContextPrompt(it))
        }
        selectedPatientId?.let {
            systemMessages += mapOf(
                "role" to "system",
                "content" to "Selected patientId for this session: $it. Prefer brief_patient with this id when the doctor says 'this patient' or asks for a briefing without naming someone.",
            )
        }

        val (loopMessages, toolEvents) = runToolLoop(doctor, language, messages, systemMessages)
        for (tool in toolEvents) {
            emit(tool)
        }

        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to props.model,
                "stream" to true,
                "temperature" to 0.2,
                "max_tokens" to 350,
                "messages" to systemMessages +
                    messages.map { mapOf("role" to it.role, "content" to it.content) } +
                    loopMessages,
            )
        )

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${props.apiKey}")
            .addHeader("OpenAI-Project", props.projectId)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw AiStreamException(code = "AI_UNAVAILABLE", message = "AI is temporarily unavailable.")
            }
            val source = response.body?.source() ?: return@use
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: continue
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:")
                if (data.isBlank() || data.trim() == "[DONE]") continue
                try {
                    val json = mapper.readTree(data)
                    val delta = json.path("choices").path(0).path("delta").path("content").asText(null)
                    if (!delta.isNullOrEmpty()) emit(MorningStreamEvent.Token(delta))
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun runToolLoop(
        doctor: DoctorProfile,
        language: OutputLanguage,
        messages: List<AiMessageDto>,
        systemMessages: List<Map<String, String>>,
    ): Pair<List<Map<String, Any?>>, List<MorningStreamEvent.Tool>> {
        val loopMessages = mutableListOf<Map<String, Any?>>()
        val toolEvents = mutableListOf<MorningStreamEvent.Tool>()
        val maxIterations = 4
        for (iteration in 1..maxIterations) {
            val payload = mapper.writeValueAsString(
                mapOf(
                    "model" to props.model,
                    "stream" to false,
                    "temperature" to 0.0,
                    "tools" to morningToolsSchema(),
                    "tool_choice" to "auto",
                    "messages" to systemMessages +
                        messages.map { mapOf("role" to it.role, "content" to it.content) } +
                        loopMessages +
                        listOf(
                            mapOf(
                                "role" to "system",
                                "content" to "Call tools when you need live schedule, patient match, free slots, or draft facts. After tools, answer in one short spoken paragraph. Never claim a booking, reschedule, or chart commit happened.",
                            )
                        ),
                )
            )
            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer ${props.apiKey}")
                .addHeader("OpenAI-Project", props.projectId)
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            val bodyText = toolClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    log.warn("Morning tool loop HTTP {}", response.code)
                    return loopMessages to toolEvents
                }
                response.body?.string().orEmpty()
            }
            val json = try {
                mapper.readTree(bodyText)
            } catch (_: Exception) {
                return loopMessages to toolEvents
            }
            val modelMsg = json.path("choices").path(0).path("message")
            val toolCalls = modelMsg.path("tool_calls")
            if (!toolCalls.isArray || toolCalls.isEmpty) {
                return loopMessages to toolEvents
            }

            val normalizedCalls = toolCalls.take(3).mapNotNull { tc ->
                val id = tc.path("id").asText(null) ?: return@mapNotNull null
                val name = tc.path("function").path("name").asText(null) ?: return@mapNotNull null
                val argsRaw = tc.path("function").path("arguments").asText("{}")
                val args = try {
                    @Suppress("UNCHECKED_CAST")
                    mapper.readValue(argsRaw, Map::class.java) as Map<String, Any?>
                } catch (_: Exception) {
                    emptyMap()
                }
                Triple(id, name, args)
            }
            if (normalizedCalls.isEmpty()) return loopMessages to toolEvents

            loopMessages += mapOf(
                "role" to "assistant",
                "content" to (modelMsg.path("content").asText(null) ?: ""),
                "tool_calls" to normalizedCalls.map { (id, name, args) ->
                    mapOf(
                        "id" to id,
                        "type" to "function",
                        "function" to mapOf(
                            "name" to name,
                            "arguments" to mapper.writeValueAsString(args),
                        ),
                    )
                },
            )

            for ((id, name, args) in normalizedCalls) {
                val result = morningTools.execute(doctor, name, args)
                loopMessages += mapOf(
                    "role" to "tool",
                    "tool_call_id" to id,
                    "content" to mapper.writeValueAsString(result),
                )
                if (morningTools.isCardTool(name)) {
                    toolEvents += MorningStreamEvent.Tool(name = name, payload = result)
                }
                log.info("morning_tool iteration={} tool={} ok={}", iteration, name, !result.containsKey("error"))
            }
        }
        return loopMessages to toolEvents
    }

    private fun morningSystemPrompt(doctor: DoctorProfile, language: OutputLanguage): String {
        val langHint = when (language) {
            OutputLanguage.EN -> "Preferred reply language hint: English."
            OutputLanguage.RU -> "Preferred reply language hint: Russian."
            OutputLanguage.UZ -> "Preferred reply language hint: Uzbek."
        }
        return """
You are Shifa, the clinical Intelligence assistant for Dr. ${doctor.firstName} ${doctor.lastName}.

LANGUAGE (MANDATORY):
- Reply in the SAME language the doctor used in their latest message (English, Russian, or Uzbek).
- $langHint
- If tool results or spokenHint are in another language, translate them into the doctor's language before answering.
- Never switch to Uzbek when the doctor spoke English, and never switch to English when the doctor spoke Uzbek/Russian.

You help with:
- Today's schedule (get_today_schedule)
- Finding patients by name (search_patients) — never invent a patientId
- Proposing a new appointment (propose_appointment) after patientId and date are known
- Proposing a reschedule (propose_reschedule) for an upcoming visit
- Draft visit facts / document everything (list_draft_visit_facts) when an appointmentId is known
- Most visited patients and clinic patterns
- Briefing a patient from the chart (brief_patient)

CRITICAL WRITE RULES:
- You NEVER book, reschedule, cancel, or commit chart facts yourself.
- Tools only propose. The doctor confirms in the UI.
- Never say "I booked", "I scheduled", "I saved", or "documented" unless a tool result already shows a confirmed write (they will not).
- Never invent patient names, ids, times, or free slots. Use tools.

Rules:
- Prefer spokenHint from tool results (translated).
- For "most visited" — ALWAYS call get_most_visited_patients.
- For booking requests: search_patients if needed, then propose_appointment with patientId and date (ISO or tomorrow/today).
- Speak in 1–3 short sentences. No markdown, no bullet lists, no diagnosis, no prescriptions.
- Sound like a calm clinical OS. Your text will be spoken aloud.
""".trimIndent()
    }

    private fun morningToolsSchema(): List<Map<String, Any?>> = listOf(
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "get_today_schedule",
                "description" to "Get today's appointment count, list, and minutes until the next visit.",
                "parameters" to mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
            ),
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "search_patients",
                "description" to "Search this doctor's patient roster by name. Use before booking when only a name is given.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf("type" to "string", "description" to "Patient name fragment"),
                    ),
                    "required" to listOf("query"),
                ),
            ),
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "propose_appointment",
                "description" to "Propose free slots to book a patient. Does NOT create the appointment. Requires patientId and date.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "patientId" to mapOf("type" to "integer"),
                        "date" to mapOf(
                            "type" to "string",
                            "description" to "ISO date YYYY-MM-DD, or today / tomorrow",
                        ),
                        "isVideo" to mapOf("type" to "boolean"),
                        "reason" to mapOf("type" to "string"),
                    ),
                    "required" to listOf("patientId", "date"),
                ),
            ),
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "propose_reschedule",
                "description" to "Propose free slots to move an upcoming appointment. Does NOT change the appointment.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "patientId" to mapOf("type" to "integer"),
                        "appointmentId" to mapOf("type" to "integer"),
                        "date" to mapOf(
                            "type" to "string",
                            "description" to "Target day ISO / today / tomorrow. Defaults to current appointment day.",
                        ),
                    ),
                ),
            ),
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "list_draft_visit_facts",
                "description" to "List draft visit facts for an appointment so the doctor can confirm Document everything. Does NOT commit.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "appointmentId" to mapOf("type" to "integer"),
                    ),
                    "required" to listOf("appointmentId"),
                ),
            ),
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "brief_patient",
                "description" to "Brief the doctor on a patient: last visit, complaints, prior plan, timeline facts.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "patientId" to mapOf("type" to "integer", "description" to "Patient profile id"),
                    ),
                    "required" to listOf("patientId"),
                ),
            ),
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "get_most_visited_patients",
                "description" to "Rank this doctor's patients by how many non-cancelled appointments they had.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "limit" to mapOf("type" to "integer"),
                        "daysBack" to mapOf("type" to "integer"),
                    ),
                ),
            ),
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "get_clinic_patterns",
                "description" to "Get anonymized clinic habit counts from previously accepted visit facts.",
                "parameters" to mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
            ),
        ),
    )
}
