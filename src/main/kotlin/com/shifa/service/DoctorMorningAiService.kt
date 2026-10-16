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
import java.time.LocalDate
import java.time.ZoneId

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
                "content" to
                    "Focus patientId for this turn: $it. " +
                    "Use THIS exact patientId for propose_appointment / propose_reschedule / brief_patient when the doctor says yes, him/her, that patient, or continues booking — do not invent or reuse any other id. " +
                    "Only switch patients after search_patients returns a different match the doctor clearly picked.",
            )
        }

        val toolOutcome = runToolLoop(
            doctor = doctor,
            messages = messages,
            systemMessages = systemMessages,
            focusPatientId = selectedPatientId,
        )
        for (tool in toolOutcome.toolEvents) {
            emit(tool)
        }

        // Fast path: first model turn already had the spoken answer and needed no tools.
        val direct = toolOutcome.directReply?.trim().orEmpty()
        if (direct.isNotEmpty() && toolOutcome.toolEvents.isEmpty()) {
            emit(MorningStreamEvent.Token(direct))
            return@flow
        }

        // Fast path: tools produced a spokenHint — translate/speak that only (skip full regen).
        val hint = toolOutcome.latestSpokenHint?.trim().orEmpty()
        if (hint.isNotEmpty()) {
            if (language == OutputLanguage.EN) {
                emit(MorningStreamEvent.Token(hint))
            } else {
                val langName = when (language) {
                    OutputLanguage.RU -> "Russian"
                    OutputLanguage.UZ -> "Uzbek"
                    OutputLanguage.EN -> "English"
                }
                val translated = completeChat(
                    messages = listOf(
                        mapOf(
                            "role" to "system",
                            "content" to "Translate the following clinical assistant line into $langName. Output ONLY the spoken sentence — no quotes, no preamble.",
                        ),
                        mapOf("role" to "user", "content" to hint),
                    ),
                    maxTokens = 120,
                    temperature = 0.1,
                )
                if (translated.isNotBlank()) emit(MorningStreamEvent.Token(translated))
            }
            return@flow
        }

        val reply = completeChat(
            messages = systemMessages +
                messages.map { mapOf("role" to it.role, "content" to it.content) } +
                toolOutcome.loopMessages,
            maxTokens = 220,
            temperature = 0.2,
        )
        if (reply.isNotBlank()) emit(MorningStreamEvent.Token(reply))
    }

    private data class ToolLoopOutcome(
        val loopMessages: List<Map<String, Any?>>,
        val toolEvents: List<MorningStreamEvent.Tool>,
        val directReply: String?,
        val latestSpokenHint: String?,
    )

    private fun runToolLoop(
        doctor: DoctorProfile,
        messages: List<AiMessageDto>,
        systemMessages: List<Map<String, String>>,
        focusPatientId: Long?,
    ): ToolLoopOutcome {
        val loopMessages = mutableListOf<Map<String, Any?>>()
        val toolEvents = mutableListOf<MorningStreamEvent.Tool>()
        // patientIds returned by search_patients in this request (same tool loop).
        val searchedPatientIds = linkedSetOf<Long>()
        var latestSpokenHint: String? = null
        // Cap at 2: search + propose is enough; more iterations made voice feel slow.
        val maxIterations = 2
        for (iteration in 1..maxIterations) {
            val payload = mapper.writeValueAsString(
                mapOf(
                    "model" to props.model,
                    "stream" to false,
                    "temperature" to 0.0,
                    "max_tokens" to 400,
                    "tools" to morningToolsSchema(),
                    "tool_choice" to "auto",
                    "messages" to systemMessages +
                        messages.map { mapOf("role" to it.role, "content" to it.content) } +
                        loopMessages +
                        listOf(
                            mapOf(
                                "role" to "system",
                                "content" to "Call tools when you need live schedule, patient match, free slots, or draft facts. Prefer one tool batch. If the doctor only says yes/ok/confirm after a time was already proposed, do NOT call propose_appointment again. If no tool is needed, answer in one short spoken paragraph now.",
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
                    return ToolLoopOutcome(loopMessages, toolEvents, null, latestSpokenHint)
                }
                response.body?.string().orEmpty()
            }
            val json = try {
                mapper.readTree(bodyText)
            } catch (_: Exception) {
                return ToolLoopOutcome(loopMessages, toolEvents, null, latestSpokenHint)
            }
            val modelMsg = json.path("choices").path(0).path("message")
            val content = modelMsg.path("content").asText(null)?.trim().orEmpty()
            val toolCalls = modelMsg.path("tool_calls")
            if (!toolCalls.isArray || toolCalls.isEmpty) {
                // No tools — reuse this answer instead of a second LLM round-trip.
                return ToolLoopOutcome(loopMessages, toolEvents, content.ifBlank { null }, latestSpokenHint)
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
            if (normalizedCalls.isEmpty()) {
                return ToolLoopOutcome(loopMessages, toolEvents, content.ifBlank { null }, latestSpokenHint)
            }

            loopMessages += mapOf(
                "role" to "assistant",
                "content" to content,
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

            val latestUserText = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
            // Run searches before booking tools so patientId can be corrected in-batch.
            val orderedCalls = normalizedCalls.sortedBy { (_, name, _) ->
                when (name) {
                    "search_patients" -> 0
                    "propose_appointment", "propose_reschedule" -> 2
                    else -> 1
                }
            }
            for ((id, name, args) in orderedCalls) {
                val safeArgs = sanitizeToolArgs(
                    toolName = name,
                    args = args,
                    latestUserText = latestUserText,
                    focusPatientId = focusPatientId,
                    searchedPatientIds = searchedPatientIds,
                )
                val result = morningTools.execute(doctor, name, safeArgs)
                if (name == "search_patients") {
                    extractSearchPatientIds(result).forEach { searchedPatientIds += it }
                }
                loopMessages += mapOf(
                    "role" to "tool",
                    "tool_call_id" to id,
                    "content" to mapper.writeValueAsString(result),
                )
                (result["spokenHint"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    latestSpokenHint = it
                }
                if (morningTools.isCardTool(name)) {
                    toolEvents += MorningStreamEvent.Tool(name = name, payload = result)
                }
                log.info("morning_tool iteration={} tool={} ok={}", iteration, name, !result.containsKey("error"))
            }
        }
        return ToolLoopOutcome(loopMessages, toolEvents, null, latestSpokenHint)
    }

    /** Non-streaming completion — morning replies are short; avoids a second long wait. */
    private fun completeChat(
        messages: List<Map<String, Any?>>,
        maxTokens: Int,
        temperature: Double,
    ): String {
        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to props.model,
                "stream" to false,
                "temperature" to temperature,
                "max_tokens" to maxTokens,
                "messages" to messages,
            )
        )
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${props.apiKey}")
            .addHeader("OpenAI-Project", props.projectId)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val bodyText = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw AiStreamException(code = "AI_UNAVAILABLE", message = "AI is temporarily unavailable.")
            }
            response.body?.string().orEmpty()
        }
        return try {
            mapper.readTree(bodyText)
                .path("choices").path(0).path("message").path("content")
                .asText("")
                .trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun morningSystemPrompt(doctor: DoctorProfile, language: OutputLanguage): String {
        val langHint = when (language) {
            OutputLanguage.EN -> "Preferred reply language hint: English."
            OutputLanguage.RU -> "Preferred reply language hint: Russian."
            OutputLanguage.UZ -> "Preferred reply language hint: Uzbek."
        }
        val zone = try {
            ZoneId.of(doctor.timeZone.ifBlank { "Asia/Tashkent" })
        } catch (_: Exception) {
            ZoneId.of("Asia/Tashkent")
        }
        val today = LocalDate.now(zone)
        return """
You are Shifa, the clinical Intelligence assistant for Dr. ${doctor.firstName} ${doctor.lastName}.

CLOCK (MANDATORY):
- Practice timezone: ${zone.id}
- Today is ${today} (${today.dayOfWeek}). Tomorrow is ${today.plusDays(1)}.
- When the doctor says today/tomorrow (or сегодня/завтра), pass date as the literal "today" or "tomorrow" — do NOT invent an ISO year from memory.
- Never use a past year. If you pass ISO, it must be ${today.year} or later.

LANGUAGE (MANDATORY):
- Reply in the SAME language the doctor used in their latest message (English, Russian, or Uzbek).
- $langHint
- If tool results or spokenHint are in another language, translate them into the doctor's language before answering.
- Never switch to Uzbek when the doctor spoke English, and never switch to English when the doctor spoke Uzbek/Russian.

You help with:
- Today's schedule (get_today_schedule)
- Finding patients by name (search_patients) — never invent a patientId. If the tool returns fuzzy suggestions, ask "Did you mean …?" and wait for the doctor to confirm before booking.
- Proposing a new appointment (propose_appointment) after patientId and date are known
- Proposing a reschedule (propose_reschedule) for an upcoming visit
- Draft visit facts / document everything (list_draft_visit_facts) when an appointmentId is known
- Most visited patients and clinic patterns
- Briefing a patient from the chart (brief_patient)

CRITICAL WRITE RULES:
- You NEVER book, reschedule, cancel, or commit chart facts yourself.
- Tools only propose. When the doctor names a free time, the client auto-confirms that slot — do not ask them to pick or say yes again.
- Never invent patient names, ids, times, or free slots. Use tools.
- patientId for propose_appointment MUST be the Focus patientId above, or an id returned by search_patients in this same turn — never invent ids and never reuse a different patient from memory.

BOOKING FLOW (MANDATORY):
- When the doctor states a time (e.g. 10:00, 10 o'clock), call propose_appointment ONCE with date AND time.
- If spokenHint / autoConfirm says the time was matched, reply that it is booked for that time — do NOT call propose_appointment again, do NOT ask "would you like to confirm".
- If they say yes / ok / please after a time was already proposed, do NOT call propose_appointment again.
- Only re-propose when they ask for a different day or time.

Rules:
- Prefer spokenHint from tool results (translated).
- For "most visited" — ALWAYS call get_most_visited_patients.
- For booking requests: search_patients if needed, then propose_appointment with patientId, date ("tomorrow"/"today"), and time when known.
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
                "description" to "Propose (and auto-confirm when time matches) a booking. Requires patientId and date. Pass time when the doctor named one.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "patientId" to mapOf("type" to "integer"),
                        "date" to mapOf(
                            "type" to "string",
                            "description" to "Prefer literal today or tomorrow. ISO YYYY-MM-DD only if the doctor gave a specific calendar day (current year or later).",
                        ),
                        "time" to mapOf(
                            "type" to "string",
                            "description" to "Preferred clock time HH:mm when the doctor said one (e.g. 10:00). Omit only if they have not named a time yet.",
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
                "description" to "Propose moving an upcoming appointment. Pass time when named. Does NOT change the appointment until auto-confirm/UI.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "patientId" to mapOf("type" to "integer"),
                        "appointmentId" to mapOf("type" to "integer"),
                        "date" to mapOf(
                            "type" to "string",
                            "description" to "Prefer today / tomorrow. ISO only for an explicit future day. Defaults to current appointment day.",
                        ),
                        "time" to mapOf(
                            "type" to "string",
                            "description" to "Preferred clock time HH:mm when the doctor said one.",
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

    /**
     * Correct date/patient args the model invents (stale years, wrong patientId).
     */
    private fun sanitizeToolArgs(
        toolName: String,
        args: Map<String, Any?>,
        latestUserText: String,
        focusPatientId: Long?,
        searchedPatientIds: Set<Long>,
    ): Map<String, Any?> {
        if (toolName != "propose_appointment" && toolName != "propose_reschedule") {
            return args
        }
        var next = args
        relativeDateFromUserText(latestUserText)?.let { next = next + ("date" to it) }
        timeFromUserText(latestUserText)?.let { next = next + ("time" to it) }

        val modelPatientId = (next["patientId"] as? Number)?.toLong()
        val resolvedPatientId = when {
            // Fresh search in this request wins.
            searchedPatientIds.size == 1 -> searchedPatientIds.first()
            modelPatientId != null && modelPatientId in searchedPatientIds -> modelPatientId
            // Otherwise pin to the chat's focus patient (last search/propose card).
            focusPatientId != null -> focusPatientId
            else -> modelPatientId
        }
        if (resolvedPatientId != null && resolvedPatientId != modelPatientId) {
            log.info(
                "morning_tool corrected patientId model={} -> resolved={} focus={} searched={}",
                modelPatientId,
                resolvedPatientId,
                focusPatientId,
                searchedPatientIds,
            )
            next = next + ("patientId" to resolvedPatientId)
        }
        return next
    }

    /** Pull a clock time from speech/text like "10:00", "10 o'clock", "10am". */
    private fun timeFromUserText(text: String): String? {
        val t = text.lowercase()
        val ampm = Regex(
            """\b([01]?\d|2[0-3])(?::([0-5]\d))?\s*(a\.?m\.?|p\.?m\.?)\b""",
        ).find(t)
        if (ampm != null) {
            var hour = ampm.groupValues[1].toInt()
            val minute = ampm.groupValues[2].ifBlank { "00" }
            val isPm = ampm.groupValues[3].startsWith("p")
            if (hour == 12) hour = 0
            if (isPm) hour += 12
            return "%02d:%s".format(hour, minute.padStart(2, '0'))
        }
        val clock = Regex(
            """\b([01]?\d|2[0-3])(?::([0-5]\d))\b""",
        ).find(t)
        if (clock != null) {
            val hour = clock.groupValues[1].toInt()
            val minute = clock.groupValues[2]
            return "%02d:%s".format(hour, minute)
        }
        val oclock = Regex("""\b([01]?\d|2[0-3])\s*o['’]?clock\b""").find(t)
        if (oclock != null) {
            return "%02d:00".format(oclock.groupValues[1].toInt())
        }
        return null
    }

    private fun extractSearchPatientIds(result: Map<String, Any?>): List<Long> {
        val matches = result["matches"] as? List<*> ?: return emptyList()
        return matches.mapNotNull { row ->
            val map = row as? Map<*, *> ?: return@mapNotNull null
            (map["patientId"] as? Number)?.toLong()
        }
    }

    private fun relativeDateFromUserText(text: String): String? {
        val t = text.lowercase()
        return when {
            Regex("""\b(tomorrow|завтра|ertaga|erta)\b""").containsMatchIn(t) -> "tomorrow"
            Regex("""\b(today|сегодня|bugun)\b""").containsMatchIn(t) -> "today"
            else -> null
        }
    }
}
