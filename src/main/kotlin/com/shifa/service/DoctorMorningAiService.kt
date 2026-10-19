package com.shifa.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.shifa.ai.MedicalPromptBuilder
import com.shifa.ai.OutputLanguage
import com.shifa.ai.PatientAiContext
import com.shifa.ai.RedFlagEngine
import com.shifa.ai.llm.LlmGateway
import com.shifa.ai.llm.LlmPurpose
import com.shifa.ai.llm.LlmRequest
import com.shifa.ai.llm.LlmTurnContext
import com.shifa.ai.llm.LlmUnavailableException
import com.shifa.ai.llm.RequestCategoryClassifier
import com.shifa.ai.llm.RouteLanguageDetector
import com.shifa.ai.memory.IntelligenceMemoryExtractor
import com.shifa.ai.memory.IntelligenceMemoryService
import com.shifa.config.OpenAiProperties
import com.shifa.domain.DoctorProfile
import com.shifa.web.AiStreamException
import com.shifa.web.dto.AiMessageDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId

/**
 * Spoken morning / Intelligence session: tool-augmented assistant.
 * Emits [MorningStreamEvent.Tool] for card UI, then short spoken [MorningStreamEvent.Token]s.
 *
 * Model calls go through [LlmGateway] (provider-agnostic, routed by language/category, bounded fallback).
 * One turn is pinned to the provider that produced the first executed tool call.
 */
@Service
class DoctorMorningAiService(
    private val props: OpenAiProperties,
    private val morningTools: DoctorMorningToolService,
    private val llm: LlmGateway,
    private val memoryService: IntelligenceMemoryService,
    private val memoryExtractor: IntelligenceMemoryExtractor,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()
    private val rateLimiter = SimpleRateLimiter(props.maxRequestsPerMinute)

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

        val latestUserText = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        val ctx = LlmTurnContext(
            doctorId = doctor.id,
            language = RouteLanguageDetector.detect(latestUserText, language),
            category = RequestCategoryClassifier.classify(latestUserText),
        )

        val systemMessages = mutableListOf(
            mapOf("role" to "system", "content" to morningSystemPrompt(doctor, language)),
        )
        patientContext?.let {
            systemMessages += mapOf("role" to "system", "content" to MedicalPromptBuilder.patientContextPrompt(it))
        }
        val memories = memoryService.retrieveForTurn(doctor, latestUserText)
        memoryService.promptBlock(memories)?.let {
            systemMessages += mapOf("role" to "system", "content" to it)
        }
        selectedPatientId?.let {
            systemMessages += mapOf(
                "role" to "system",
                "content" to
                    "Focus patientId for this turn: $it. " +
                    "Use THIS exact patientId for propose_appointment / propose_reschedule / brief_patient / " +
                    "list_patient_documents / open_patient_document / open_dental_chart when the doctor says yes, him/her, that patient, open the diagram/document, or continues about this patient — do not invent or reuse any other id. " +
                    "Only switch patients after search_patients returns a different match the doctor clearly picked.",
            )
        }

        val toolOutcome = runToolLoop(
            doctor = doctor,
            messages = messages,
            systemMessages = systemMessages,
            focusPatientId = selectedPatientId,
            ctx = ctx,
        )
        for (tool in toolOutcome.toolEvents) {
            emit(tool)
        }

        var spoken = ""
        try {
            // Fast path: first model turn already had the spoken answer and needed no tools.
            val direct = toolOutcome.directReply?.trim().orEmpty()
            if (direct.isNotEmpty() && toolOutcome.toolEvents.isEmpty()) {
                spoken = direct
                emit(MorningStreamEvent.Token(direct))
                return@flow
            }

            // Fast path: tools produced a spokenHint — translate/speak that only (skip full regen).
            val hint = toolOutcome.latestSpokenHint?.trim().orEmpty()
            if (hint.isNotEmpty()) {
                if (language == OutputLanguage.EN) {
                    spoken = hint
                    emit(MorningStreamEvent.Token(hint))
                } else {
                    val langName = when (language) {
                        OutputLanguage.RU -> "Russian"
                        OutputLanguage.UZ -> "Uzbek"
                        OutputLanguage.EN -> "English"
                    }
                    val translatePrompt =
                        "Translate the following clinical assistant line into $langName. " +
                            "Keep every patient name, weekday, and time. Do not summarize or drop the list. " +
                            "Output ONLY the spoken sentence — no quotes, no preamble."
                    var translated = completeChat(
                        messages = listOf(
                            mapOf("role" to "system", "content" to translatePrompt),
                            mapOf("role" to "user", "content" to hint),
                        ),
                        maxTokens = 400,
                        temperature = 0.1,
                        ctx = ctx,
                    )
                    if (!spokenTranslationUsable(hint, translated)) {
                        val retry = completeChat(
                            messages = listOf(
                                mapOf("role" to "system", "content" to translatePrompt),
                                mapOf("role" to "user", "content" to hint),
                            ),
                            maxTokens = 600,
                            temperature = 0.1,
                            ctx = ctx,
                        )
                        translated = if (spokenTranslationUsable(hint, retry)) retry else hint
                    }
                    spoken = translated
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
                ctx = ctx,
            )
            spoken = reply
            if (reply.isNotBlank()) emit(MorningStreamEvent.Token(reply))
        } finally {
            scheduleMemoryExtraction(doctor, latestUserText, spoken, toolOutcome, ctx)
        }
    }

    /** Best-effort, off the critical path; never throws into the stream. */
    private fun scheduleMemoryExtraction(
        doctor: DoctorProfile,
        latestUserText: String,
        spoken: String,
        outcome: ToolLoopOutcome,
        ctx: LlmTurnContext,
    ) {
        try {
            if (!memoryService.enabled() || !memoryExtractor.shouldExtract(latestUserText)) return
            memoryExtractor.extractAsync(
                doctor = doctor,
                latestUserText = latestUserText,
                assistantReply = spoken,
                toolNames = outcome.toolEvents.map { it.name }.distinct(),
                patientNamesInTurn = outcome.patientNames,
                requestId = ctx.requestId,
            )
        } catch (e: Exception) {
            log.warn("intelligence_memory_schedule_failed reason={}", e.javaClass.simpleName)
        }
    }

    private data class ToolLoopOutcome(
        val loopMessages: List<Map<String, Any?>>,
        val toolEvents: List<MorningStreamEvent.Tool>,
        val directReply: String?,
        val latestSpokenHint: String?,
        /** Patient names surfaced by tools this turn; memory extraction must never store them. */
        val patientNames: Set<String> = emptySet(),
    )

    /**
     * Cancellation is resolved here so "cancel it" and "next week" cannot be dropped
     * or replaced by a list of a different patient.
     */
    private fun forcedCancellation(
        doctor: DoctorProfile,
        messages: List<AiMessageDto>,
        focusPatientId: Long?,
    ): ToolLoopOutcome? {
        val latest = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        val priorAssistant = messages.filter { it.role == "assistant" }.map { it.content }
        val priorUsers = messages.filter { it.role == "user" }.map { it.content }.dropLast(1)
        val confirm = com.shifa.intelligence.appointment.AppointmentActionService.isExplicitConfirmation(latest) &&
            com.shifa.intelligence.appointment.AppointmentActionService.actionToken(priorAssistant) != null
        val preview = com.shifa.intelligence.appointment.CancellationIntent.shouldPreview(
            latest, priorUsers, priorAssistant,
        )
        if (!confirm && !preview) return null
        val tool = if (confirm) "confirm_appointment_cancellation" else "preview_appointment_cancellation"
        val result = morningTools.execute(
            doctor = doctor,
            toolName = tool,
            args = emptyMap(),
            priorAssistantMessages = priorAssistant,
            priorUserMessages = priorUsers,
            latestUserText = latest,
            focusPatientId = focusPatientId,
        )
        return ToolLoopOutcome(
            loopMessages = emptyList(),
            toolEvents = listOf(MorningStreamEvent.Tool(name = tool, payload = result)),
            directReply = null,
            latestSpokenHint = result["spokenHint"] as? String,
        )
    }

    /**
     * A move is resolved here, before the model can answer from memory.
     * That is what turned "Franco on 23 October" into "no such appointment".
     */
    private fun forcedReschedule(
        doctor: DoctorProfile,
        messages: List<AiMessageDto>,
        focusPatientId: Long?,
    ): ToolLoopOutcome? {
        val latest = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        val priorAssistant = messages.filter { it.role == "assistant" }.map { it.content }
        val priorUsers = messages.filter { it.role == "user" }.map { it.content }.dropLast(1)
        if (!com.shifa.intelligence.appointment.RescheduleIntent.shouldMove(latest, priorUsers, priorAssistant)) {
            return null
        }
        val result = morningTools.execute(
            doctor = doctor,
            toolName = "propose_reschedule",
            args = emptyMap(),
            priorAssistantMessages = priorAssistant,
            priorUserMessages = priorUsers,
            latestUserText = latest,
            focusPatientId = focusPatientId,
        )
        return ToolLoopOutcome(
            loopMessages = emptyList(),
            toolEvents = listOf(MorningStreamEvent.Tool(name = "propose_reschedule", payload = result)),
            directReply = null,
            latestSpokenHint = result["spokenHint"] as? String,
        )
    }

    private fun runToolLoop(
        doctor: DoctorProfile,
        messages: List<AiMessageDto>,
        systemMessages: List<Map<String, String>>,
        focusPatientId: Long?,
        ctx: LlmTurnContext,
    ): ToolLoopOutcome {
        forcedCancellation(doctor, messages, focusPatientId)?.let { return it }
        forcedReschedule(doctor, messages, focusPatientId)?.let { return it }
        val loopMessages = mutableListOf<Map<String, Any?>>()
        val toolEvents = mutableListOf<MorningStreamEvent.Tool>()
        // patientIds returned by search_patients in this request (same tool loop).
        val searchedPatientIds = linkedSetOf<Long>()
        val patientNames = linkedSetOf<String>()
        // (tool name + canonical args) already executed this turn: never run the same call twice,
        // even if the model repeats it or a later iteration re-emits it.
        val executedKeys = hashSetOf<String>()
        var latestSpokenHint: String? = null
        // Cap at 2: search + propose is enough; more iterations made voice feel slow.
        val maxIterations = 2
        for (iteration in 1..maxIterations) {
            val request = LlmRequest(
                messages = systemMessages +
                    messages.map { mapOf("role" to it.role, "content" to it.content) } +
                    loopMessages +
                    listOf(
                        mapOf(
                            "role" to "system",
                            "content" to toolLoopNudge(messages),
                        )
                    ),
                tools = morningToolsSchema(),
                toolChoice = "auto",
                temperature = 0.0,
                maxTokens = 400,
            )
            val response = try {
                llm.complete(request, ctx, LlmPurpose.TOOL_LOOP)
            } catch (e: LlmUnavailableException) {
                log.warn("Morning tool loop unavailable iteration={} attempts={}", iteration, e.attempts)
                return ToolLoopOutcome(loopMessages, toolEvents, null, latestSpokenHint, patientNames)
            }
            val content = response.content
            if (response.toolCalls.isEmpty()) {
                // No tools — reuse this answer instead of a second LLM round-trip.
                return ToolLoopOutcome(loopMessages, toolEvents, content.ifBlank { null }, latestSpokenHint, patientNames)
            }
            // From here on this turn is pinned: tool results below only make sense to this provider.
            ctx.pinnedProvider = response.providerId

            val normalizedCalls = response.toolCalls.take(3).map { Triple(it.id, it.name, it.arguments) }

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
            for ((id, modelToolName, args) in orderedCalls) {
                var name = modelToolName
                var safeArgs = sanitizeToolArgs(
                    toolName = name,
                    args = args,
                    latestUserText = latestUserText,
                    focusPatientId = focusPatientId,
                    searchedPatientIds = searchedPatientIds,
                )
                // A per-patient listing needs a patient the doctor actually picked. When the model
                // invents one (no search, no focus), answer the general question instead.
                if (name == "list_patient_appointments" &&
                    !hasVerifiedPatient(safeArgs, focusPatientId, searchedPatientIds)
                ) {
                    log.info(
                        "morning_tool rerouted list_patient_appointments -> query_appointment_intelligence (unverified patientId={})",
                        safeArgs["patientId"],
                    )
                    name = "query_appointment_intelligence"
                    safeArgs = generalAppointmentListArgs(safeArgs)
                }
                val executionKey = name + "|" + canonicalArgs(safeArgs)
                if (!executedKeys.add(executionKey)) {
                    log.info("morning_tool iteration={} tool={} skipped=duplicate_in_turn", iteration, name)
                    loopMessages += mapOf(
                        "role" to "tool",
                        "tool_call_id" to id,
                        "content" to mapper.writeValueAsString(mapOf("skipped" to "already executed in this turn")),
                    )
                    continue
                }
                val result = morningTools.execute(
                    doctor = doctor,
                    toolName = name,
                    args = safeArgs,
                    priorAssistantMessages = messages.filter { it.role == "assistant" }.map { it.content },
                    priorUserMessages = messages.filter { it.role == "user" }.map { it.content }.dropLast(1),
                    latestUserText = latestUserText,
                    focusPatientId = focusPatientId,
                    searchedPatientIds = searchedPatientIds,
                )
                if (name == "search_patients") {
                    extractSearchPatientIds(result).forEach { searchedPatientIds += it }
                    extractSearchPatientNames(result).forEach { patientNames += it }
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
            // Search is a stepping-stone (search then book). Any other successful card/hint is the answer —
            // skip a second model round-trip that only restates the spokenHint.
            val searchedThisIteration = orderedCalls.any { it.second == "search_patients" }
            if (!searchedThisIteration && (toolEvents.isNotEmpty() || !latestSpokenHint.isNullOrBlank())) {
                return ToolLoopOutcome(loopMessages, toolEvents, null, latestSpokenHint, patientNames)
            }
        }
        return ToolLoopOutcome(loopMessages, toolEvents, null, latestSpokenHint, patientNames)
    }

    /** Stable string for the duplicate-execution guard (sorted keys, no whitespace noise). */
    private fun canonicalArgs(args: Map<String, Any?>): String =
        args.entries.sortedBy { it.key }.joinToString(",") { (k, v) -> "$k=${v?.toString()?.trim()}" }

    /**
     * Non-streaming completion — morning replies are short; avoids a second long wait.
     * Read-only, so it may fall back across providers even when the tool loop was pinned.
     */
    private fun completeChat(
        messages: List<Map<String, Any?>>,
        maxTokens: Int,
        temperature: Double,
        ctx: LlmTurnContext,
    ): String {
        val spokenCtx = LlmTurnContext(
            doctorId = ctx.doctorId,
            language = ctx.language,
            category = ctx.category,
            requestId = ctx.requestId,
            startedAtMs = ctx.startedAtMs,
        )
        return try {
            llm.complete(
                LlmRequest(messages = messages, temperature = temperature, maxTokens = maxTokens),
                spokenCtx,
                LlmPurpose.SPOKEN_REPLY,
            ).content
        } catch (e: LlmUnavailableException) {
            throw AiStreamException(code = "AI_UNAVAILABLE", message = "AI is temporarily unavailable.")
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
        val nextDays = (0L..6L).joinToString("; ") { offset ->
            val d = today.plusDays(offset)
            val name = d.dayOfWeek.name.lowercase().replaceFirstChar { c -> c.uppercaseChar() }
            "$name=$d"
        }
        return """
You are Shifa, the clinical Intelligence assistant for Dr. ${doctor.firstName} ${doctor.lastName}.
Your name is Shifa. When the doctor says "Hey Shifa", "Hi Shifa", "Salom Shifa", "Assalom Shifa", or similar, they are addressing you — respond as Shifa and follow their request.

CLOCK (MANDATORY):
- Practice timezone: ${zone.id}
- Today is ${today} (${today.dayOfWeek}). Tomorrow is ${today.plusDays(1)} (${today.plusDays(1).dayOfWeek}).
- Next 7 days (use these exact ISO dates — never invent weekdays): $nextDays
- When the doctor says today/tomorrow, pass date as "today" or "tomorrow".
- When they say a weekday (e.g. Tuesday) or "next Tuesday" / "next week", pass that phrase as date (e.g. "tuesday", "next tuesday") — the server resolves it. Do NOT invent a wrong ISO date.
- When they say a day and month with no year (23 October, 23-oktabr, 23 of October), pass that phrase. Never invent a year such as 2023 or 2024.
- Never use a past year. If you pass ISO, it must match the calendar above.

LANGUAGE (MANDATORY):
- Reply in the SAME language the doctor used in their latest message (English, Russian, or Uzbek).
- $langHint
- If tool results or spokenHint are in another language, translate them into the doctor's language before answering.
- Never switch to Uzbek when the doctor spoke English, and never switch to English when the doctor spoke Uzbek/Russian.

ANSWER ONLY WHAT WAS ASKED (STRICT):
- Free slots / open times / availability → call list_free_slots. Do NOT call get_today_schedule. Do NOT mention booked appointments unless asked.
- Today's clinic schedule / who is next / how many visits TODAY (no patient named) → call get_today_schedule. Do NOT list free slots unless asked.
- How many / which appointments WITH a patient (him/her/focus) this week / next week / on a day → call list_patient_appointments. Do NOT call get_today_schedule for that.
- "What appointments do I have this week / next week / on Friday" with NO patient named → call query_appointment_intelligence (timeRange.expression = their words). NEVER call list_patient_appointments with a guessed patientId.
- Appointment counts for other periods (yesterday, last week, last month, rolling ranges), statuses, documentation, payments, grouping, or comparisons → call query_appointment_intelligence once. Pass the doctor's period words in timeRange.expression. Do NOT invent counts, names, or dates. Do NOT send a doctorId. Do NOT use get_today_schedule for those.
- Never volunteer today's schedule when the doctor asked for free slots (or the reverse).

You help with:
- Free / open bookable slots (list_free_slots) — use for "free slots", "available times", "when am I free"
- Today's booked clinic schedule (get_today_schedule) — ONLY when the doctor asks what is on today / their schedule / who is next (whole clinic, not one patient)
- A patient's booked visits in a period (list_patient_appointments) — "how many appointments with him next week", "his visits this week"
- Finding patients by name (search_patients) — never invent a patientId. If the tool returns fuzzy suggestions, ask "Did you mean …?" and wait for the doctor to confirm before booking.
- Proposing a new appointment (propose_appointment) after patientId and date are known
- Proposing a reschedule (propose_reschedule) for an upcoming visit
- Draft visit facts / document everything (list_draft_visit_facts) when an appointmentId is known
- Most visited patients and clinic patterns
- Briefing a patient from the chart (brief_patient)
- Opening patient documents or dental diagrams IN the chat (list_patient_documents / open_patient_document / open_dental_chart) — never tell the doctor to leave the chat

DOCUMENTS & DENTAL DIAGRAM (MANDATORY):
- "Open the dental diagram / odontogram / dental chart / зубная схема" for the focus patient → call open_dental_chart with that patientId.
- "Open / show / view [this] document / X-ray / MRI / lab result" → call open_patient_document (pass query from their words when useful).
- "What documents does this patient have?" → call list_patient_documents.
- Always use Focus patientId when they mean this patient / him / her / the chart we were discussing.
- Do NOT invent document ids. Do NOT say documents open in a separate window — the client shows an inline viewer card.

CRITICAL WRITE RULES:
- You NEVER book, reschedule, cancel, or commit chart facts yourself.
- Cancelling visits uses preview_appointment_cancellation first. Nothing is cancelled until the doctor says yes in a later message and you call confirm_appointment_cancellation.
- Moving a visit still uses propose_reschedule. Do not cancel it first.
- Keep the visit already identified in this thread. A new day or time (24-oktabr, 8:30) is the destination — do not ask the patient and day again.
- Start an in-person visit with start_appointment (sets IN_PROGRESS). Open without starting with open_appointment. Video visits start when the doctor joins the call.
- Complete a visit only with complete_appointment after an explicit finish command.
- Request a patient signature only with request_appointment_signature after an explicit signature command.
- Dictation uses append_consultation_draft and appends the official consultation note. It does not complete the visit and does not request a signature.
- Tools only propose. When the doctor names a free time, the client auto-confirms that slot — do not ask them to pick or say yes again.
- Never invent patient names, ids, times, or free slots. Use tools.
- patientId for propose_appointment MUST be the Focus patientId above, or an id returned by search_patients in this same turn — never invent ids and never reuse a different patient from memory.

BOOKING FLOW (MANDATORY — follow turn by turn):
1. Doctor wants to make/book/schedule an appointment → ask for the patient name (no tools yet unless name already given).
2. Doctor gives a name → call search_patients with that name. Do NOT call get_today_schedule.
3. After a clear match → ask which date and time they want. Do NOT show today's full schedule. Do NOT call propose_appointment until a day is known.
4. Doctor gives a day (and optional time) → call propose_appointment with patientId, date, and time when known.
5. If spokenHint / autoConfirm matched a time, say it is booked — do NOT ask to confirm again.
6. If they say yes/ok after a time was already proposed, do NOT call propose_appointment again.
- An existing visit with that patient today does NOT replace booking a new one — stay on the booking flow.

Rules:
- Prefer spokenHint from tool results (translated).
- For "most visited" — ALWAYS call get_most_visited_patients.
- Speak in 1–3 short sentences. No markdown, no bullet lists, no diagnosis, no prescriptions.
- Sound like a calm clinical OS. Your text will be spoken aloud.
""".trimIndent()
    }

    private fun morningToolsSchema(): List<Map<String, Any?>> = listOf(
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "get_today_schedule",
                "description" to "Get today's BOOKED clinic schedule. Use ONLY when the doctor asks about today's schedule / how many visits today / who is next — with NO specific patient. Never use for free/open slots. Never use for 'appointments with him/her next week'. Never use while naming a patient to book a NEW appointment.",
                "parameters" to mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
            ),
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "list_patient_appointments",
                "description" to "List BOOKED appointments for ONE specific patient in a date range (this doctor). Use ONLY when the doctor names a patient or means the Focus patient (him/her/this patient). Never invent a patientId: use the Focus patientId or a search_patients result; if the doctor named someone not yet searched, pass patientName instead. When the doctor asks about their appointments in general (no patient), call query_appointment_intelligence instead.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "patientId" to mapOf("type" to "integer"),
                        "patientName" to mapOf(
                            "type" to "string",
                            "description" to "Spoken patient name when no verified patientId is available.",
                        ),
                        "date" to mapOf(
                            "type" to "string",
                            "description" to "next week / this week / today / tomorrow / weekday / day+month (23 October, 23-oktabr) / ISO, or an open range: upcoming (all future visits), from tomorrow, from monday. Day+month with no year is this calendar year. Use upcoming when the doctor says in the future / upcoming / onwards. Defaults to this week.",
                        ),
                    ),
                ),
            ),
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "list_free_slots",
                "description" to "List free/open bookable time slots for a day. Use when the doctor asks for free slots, availability, or open times. Do NOT use get_today_schedule for that.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "date" to mapOf(
                            "type" to "string",
                            "description" to "today / tomorrow / end of next week / weekday / day+month (23 October, 23-oktabr) / ISO YYYY-MM-DD of the current year. Defaults to today.",
                        ),
                    ),
                ),
            ),
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "search_patients",
                "description" to "Search this doctor's patient roster by name. Required when booking and the doctor just gave a patient name.",
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
                            "description" to "Prefer literal today, tomorrow, or day+month (23 October, 23-oktabr). ISO YYYY-MM-DD only if the doctor gave a specific calendar day (current year or later — never a past year).",
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
                "description" to "Propose moving an upcoming appointment already named in this thread. A later day/time is the destination. Pass time when named. Does NOT change the appointment until auto-confirm/UI.",
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
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "list_patient_documents",
                "description" to "List viewable documents for a patient (titles, dates, categories). Use when the doctor asks what documents exist. Prefer Focus patientId.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "patientId" to mapOf("type" to "integer"),
                        "query" to mapOf(
                            "type" to "string",
                            "description" to "Optional title/category filter (e.g. MRI, x-ray).",
                        ),
                    ),
                    "required" to listOf("patientId"),
                ),
            ),
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "open_patient_document",
                "description" to "Open one patient document as an inline chat viewer card (PDF/image). Use when the doctor asks to open/show/view a document. Prefer Focus patientId. Pass query from their words when documentId is unknown.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "patientId" to mapOf("type" to "integer"),
                        "documentId" to mapOf("type" to "integer"),
                        "query" to mapOf(
                            "type" to "string",
                            "description" to "Title or category fragment when documentId is unknown.",
                        ),
                    ),
                    "required" to listOf("patientId"),
                ),
            ),
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "preview_appointment_cancellation",
                "description" to "Preview visits that can be cancelled. Does not cancel anything. Use when the doctor asks to cancel one visit or all visits in a period. Ask them to say yes before anything is cancelled.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "patientId" to mapOf("type" to "integer"),
                        "patientName" to mapOf("type" to "string"),
                        "appointmentId" to mapOf("type" to "integer"),
                    ),
                ),
            ),
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "confirm_appointment_cancellation",
                "description" to "Cancel the visits from the preview, only after the doctor explicitly says yes in a later message. Never call this in the same turn as the original cancel request.",
                "parameters" to mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
            ),
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "open_appointment",
                "description" to "Open the next visit, or a named patient's visit, as a navigation card. Does not start the visit. Use for open my next appointment, open the patient, or open documentation. Video visits return route video; in-person returns route inPerson.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "patientId" to mapOf("type" to "integer"),
                        "patientName" to mapOf("type" to "string"),
                        "appointmentId" to mapOf("type" to "integer"),
                        "target" to mapOf("type" to "string", "description" to "appointment, patient, or documentation"),
                    ),
                ),
            ),
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "start_appointment",
                "description" to "Start an eligible in-person visit (sets IN_PROGRESS) and return an open_appointment card with route inPerson. Use when the doctor says start the visit, begin the consultation, or start the appointment. Never use for video visits. Does not complete the visit or request a signature.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "patientId" to mapOf("type" to "integer"),
                        "patientName" to mapOf("type" to "string"),
                        "appointmentId" to mapOf("type" to "integer"),
                    ),
                ),
            ),
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "complete_appointment",
                "description" to "Mark an in-progress visit COMPLETED. Call only after an explicit finish/complete/end visit command. Never call after note dictation alone.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "appointmentId" to mapOf("type" to "integer"),
                    ),
                ),
            ),
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "request_appointment_signature",
                "description" to "Ask the patient to sign the visit note via the existing notification flow. Call only after an explicit signature request. Never imply this from saving a note.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "appointmentId" to mapOf("type" to "integer"),
                    ),
                ),
            ),
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "append_consultation_draft",
                "description" to "Append the doctor's dictated words to the official consultation note for the focused visit. Does not complete the visit and does not request a signature. Pass text as the clinical words only. Set readBack true to read the current note.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "text" to mapOf("type" to "string"),
                        "appointmentId" to mapOf("type" to "integer"),
                        "readBack" to mapOf("type" to "boolean"),
                    ),
                ),
            ),
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "query_appointment_intelligence",
                "description" to "Count or list this doctor's appointments for a period with optional status, documentation, payment, patient, or visit-type filters. Use for yesterday, last week, next week, last month, rolling ranges, cancelled, completed, confirmed, no-show, undocumented, unpaid, collected money, grouping, and comparisons. Do not use for today's schedule overview, free slots, or booking. Do not send doctorId. The server resolves dates and computes numbers.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "metric" to mapOf(
                            "type" to "string",
                            "description" to "COUNT, DISTINCT_PATIENT_COUNT, GROUPED_COUNT, CANCELLATION_RATE, SUM_COLLECTED, SUM_APPOINTMENT_VALUE, SUM_OUTSTANDING, or LIST.",
                        ),
                        "shape" to mapOf(
                            "type" to "string",
                            "description" to "AGGREGATE, GROUPED, or LIST.",
                        ),
                        "timeRange" to mapOf(
                            "type" to "object",
                            "description" to "Prefer expression set to the doctor's words (yesterday, last week, last 7 days, last month). Or period such as YESTERDAY, LAST_WEEK, LAST_30_DAYS, NEXT_N_DAYS with n.",
                        ),
                        "comparisonPeriod" to mapOf(
                            "type" to "object",
                            "description" to "Optional second period, same shape as timeRange, when the doctor asks to compare.",
                        ),
                        "filters" to mapOf(
                            "type" to "object",
                            "description" to "Optional patientId or patientName, statuses (REQUESTED, CONFIRMED, IN_PROGRESS, CANCELLED, COMPLETED), documentation (NO_CONSULTATION_NOTE, HAS_CONSULTATION_NOTE, PATIENT_SIGNED, PATIENT_UNSIGNED), paymentStatus (NOT_REQUIRED, PENDING, PAID, FAILED, REFUNDED), modality (VIDEO, IN_PERSON).",
                        ),
                        "groupBy" to mapOf(
                            "type" to "string",
                            "description" to "STATUS, DAY, WEEK, MONTH, PAYMENT_STATUS, MODALITY, or PATIENT.",
                        ),
                        "sort" to mapOf("type" to "string", "description" to "START_AT_ASC or START_AT_DESC for lists."),
                        "limit" to mapOf("type" to "integer"),
                    ),
                ),
            ),
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "open_dental_chart",
                "description" to "Open the patient's dental FDI diagram / odontogram inline in chat from the latest visit documentation. Use for dental diagram, dental chart, odontogram, зубная схема. Prefer Focus patientId.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "patientId" to mapOf("type" to "integer"),
                        "appointmentId" to mapOf(
                            "type" to "integer",
                            "description" to "Optional specific visit; defaults to latest with dental documentation.",
                        ),
                    ),
                    "required" to listOf("patientId"),
                ),
            ),
        ),
    )

    /** Extra turn-specific guidance so booking/free-slots do not derail into today's schedule. */
    private fun toolLoopNudge(messages: List<AiMessageDto>): String {
        val base =
            "Call tools when you need live schedule, free slots, appointment statistics, patient match, documents, dental chart, or draft facts. Prefer one tool batch. " +
                "If the doctor only says yes/ok/confirm after a time was already proposed, do NOT call propose_appointment again. " +
                "If no tool is needed, answer in one short spoken paragraph now. Answer ONLY what was asked."
        val latest = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        if (asksForDentalChart(latest)) {
            return "$base DENTAL DIAGRAM REQUEST: call open_dental_chart with Focus patientId (or search_patients first if no focus). " +
                "Do NOT call get_today_schedule."
        }
        if (asksForDocuments(latest)) {
            return "$base DOCUMENT REQUEST: call open_patient_document (to open) or list_patient_documents (to list) with Focus patientId. " +
                "Show inline in chat — do not send them elsewhere."
        }
        if (asksForFreeSlots(latest)) {
            return "$base FREE SLOTS REQUEST: call list_free_slots with the requested day (default today). " +
                "Do NOT call get_today_schedule. Do NOT mention booked appointments."
        }
        if (asksForPatientAppointments(latest)) {
            return "$base PATIENT APPOINTMENTS REQUEST: call list_patient_appointments with Focus patientId " +
                "and date=next week / this week / the day they named. Do NOT call get_today_schedule."
        }
        if (com.shifa.intelligence.appointment.AppointmentActionService.isExplicitConfirmation(latest) &&
            messages.any { it.content.contains("<appointment_action>") }
        ) {
            return "$base CANCELLATION CONFIRMATION: call confirm_appointment_cancellation only. Do not preview again."
        }
        if (Regex("""\bcancel\b""", RegexOption.IGNORE_CASE).containsMatchIn(latest)) {
            return "$base CANCELLATION REQUEST: call preview_appointment_cancellation only. Do not confirm in this same turn."
        }
        com.shifa.intelligence.appointment.MorningVisitIntent.nudge(latest)?.let { return "$base $it" }
        if (asksForAppointmentAnalytics(latest)) {
            return "$base APPOINTMENT ANALYTICS: call query_appointment_intelligence once. " +
                "Put the doctor's period words in timeRange.expression. " +
                "Do NOT call get_today_schedule. Do NOT invent counts. Do NOT send a doctorId."
        }
        if (!conversationHasBookingIntent(messages)) return base
        if (looksLikeCalendarOrDateReply(latest)) {
            return "$base BOOKING IN PROGRESS: the doctor named a day/time or corrected the calendar. " +
                "Call list_free_slots or propose_appointment with Focus patientId and that date. " +
                "Do NOT call search_patients. Do NOT invent weekday→ISO mappings — pass tuesday/next tuesday/today/tomorrow."
        }
        if (looksLikeIncompleteUtterance(latest)) {
            return "$base BOOKING IN PROGRESS: the doctor's last line looks incomplete. " +
                "Ask a short clarifying question. Do NOT call search_patients."
        }
        if (looksLikePatientNameReply(latest)) {
            return "$base BOOKING IN PROGRESS: the doctor just gave a patient name after asking to make an appointment. " +
                "Call search_patients with that name NOW. Do NOT call get_today_schedule. " +
                "After a match, ask which date and time — do not propose until a day is given."
        }
        return "$base BOOKING IN PROGRESS: stay on booking (propose_appointment / list_free_slots with Focus patientId). " +
            "Do NOT call search_patients again unless they named a different patient. " +
            "Do NOT call get_today_schedule unless they explicitly ask what is on today."
    }

    private fun asksForDentalChart(text: String): Boolean {
        val t = text.lowercase()
        return listOf(
            "dental diagram",
            "dental chart",
            "dental schema",
            "odontogram",
            "tooth chart",
            "teeth chart",
            "зубн",
            "одонтограмм",
            "схем зуб",
            "tish sxema",
            "tish diagram",
        ).any { t.contains(it) }
    }

    private fun asksForDocuments(text: String): Boolean {
        val t = text.lowercase()
        if (asksForDentalChart(t)) return false
        return listOf(
            "open document",
            "show document",
            "view document",
            "open the document",
            "patient document",
            "his document",
            "her document",
            "their document",
            "documents",
            "x-ray",
            "xray",
            "mri",
            "lab result",
            "blood test",
            "ct scan",
            "документ",
            "рентген",
            "снимок",
            "анализ",
            "hujjat",
        ).any { t.contains(it) }
    }

    private fun asksForFreeSlots(text: String): Boolean {
        val t = text.lowercase()
        return listOf(
            "free slot",
            "free slots",
            "available slot",
            "available time",
            "open slot",
            "open time",
            "when am i free",
            "availability",
            "свободн",
            "bo'sh",
            "bosh vaqt",
        ).any { t.contains(it) }
    }

    private fun conversationHasBookingIntent(messages: List<AiMessageDto>): Boolean {
        val window = messages.takeLast(8).joinToString("\n") { it.content }.lowercase()
        return listOf(
            "appointment",
            "book",
            "schedule",
            "make an appointment",
            "запис",
            "прием",
            "приём",
            "uchrashuv",
            "yozib",
        ).any { window.contains(it) }
    }

    private fun looksLikePatientNameReply(text: String): Boolean {
        val t = text.trim()
        if (t.length < 2 || t.length > 64) return false
        if (looksLikeCalendarOrDateReply(t) || looksLikeIncompleteUtterance(t)) return false
        if (Regex("""\d{1,2}:\d{2}""").containsMatchIn(t)) return false
        if (Regex(
                """\b(today|tomorrow|yesterday|schedule|calendar|how many|appointments?|slots?|yes|ok|okay|confirm|please)\b""",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(t)
        ) {
            return false
        }
        val words = t.split(Regex("""\s+""")).filter { it.isNotBlank() }
        return words.size in 1..4
    }

    /** Weekday / relative day / time-of-day — not a patient name. */
    private fun looksLikeCalendarOrDateReply(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return false
        if (Regex(
                """\b(today|tomorrow|yesterday|monday|tuesday|wednesday|thursday|friday|saturday|sunday|""" +
                    """next week|this week|morning|afternoon|evening|""" +
                    """сегодня|завтра|понедельник|вторник|среда|четверг|пятница|суббота|воскресенье|""" +
                    """утро|вечер|dushanba|seshanba|chorshanba|payshanba|juma|shanba|yakshanba)\b""",
            ).containsMatchIn(t)
        ) {
            return true
        }
        // "17th of October", "18 October", "Oct 20"
        if (Regex(
                """\b(\d{1,2})(st|nd|rd|th)?\s+(of\s+)?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\b""",
            ).containsMatchIn(t)
        ) {
            return true
        }
        if (Regex("""\b\d{4}-\d{2}-\d{2}\b""").containsMatchIn(t)) return true
        return false
    }

    /** Trailing fragments like "what about" / "17th of October is" — clarify, don't search. */
    private fun looksLikeIncompleteUtterance(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty() || t.length > 48) return false
        if (t.endsWith(" is") || t.endsWith(" about") || t.endsWith(" for") ||
            t.endsWith(" on") || t.endsWith(" at") || t.endsWith(" to")
        ) {
            return true
        }
        return t in setOf("what about", "how about", "and", "or", "what about the")
    }

    /** True only when the patientId came from a search in this chat or the client's focus patient. */
    private fun hasVerifiedPatient(
        args: Map<String, Any?>,
        focusPatientId: Long?,
        searchedPatientIds: Set<Long>,
    ): Boolean {
        val id = (args["patientId"] as? Number)?.toLong() ?: return false
        return id == focusPatientId || id in searchedPatientIds
    }

    /**
     * Turn an unverified per-patient listing into a general listing for the same period,
     * keeping a spoken patient name (if the model passed one) so the server can resolve it.
     */
    private fun generalAppointmentListArgs(args: Map<String, Any?>): Map<String, Any?> {
        val period = (args["date"] as? String)?.trim().orEmpty().ifEmpty { "this week" }
        val filters = mutableMapOf<String, Any?>(
            "statuses" to listOf("REQUESTED", "CONFIRMED", "IN_PROGRESS", "COMPLETED"),
        )
        (args["patientName"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { filters["patientName"] = it }
        return mapOf(
            "metric" to "LIST",
            "shape" to "LIST",
            "timeRange" to mapOf("expression" to period),
            "filters" to filters,
            "sort" to "START_AT_ASC",
        )
    }

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
        if (toolName == "list_free_slots") {
            var next = args
            relativeDateFromUserText(latestUserText)?.let { next = next + ("date" to it) }
            // Prefer weekday token over a model-invented wrong ISO when speech named a weekday.
            weekdayTokenFromUserText(latestUserText)?.let { next = next + ("date" to it) }
            partOfDayFromUserText(latestUserText)?.let { next = next + ("partOfDay" to it) }
            if (next["date"] == null) next = next + ("date" to "today")
            return next
        }

        if (toolName == "list_patient_appointments") {
            var next = args
            relativeDateFromUserText(latestUserText)?.let { next = next + ("date" to it) }
            weekdayTokenFromUserText(latestUserText)?.let { next = next + ("date" to it) }
            // "In the future" / "from tomorrow onwards" is an open range, not one day or one week.
            openEndedRangeFromUserText(latestUserText)?.let { next = next + ("date" to it) }
            if (next["date"] == null) next = next + ("date" to "this week")
            val modelPatientId = (next["patientId"] as? Number)?.toLong()
            val resolvedPatientId = when {
                searchedPatientIds.size == 1 -> searchedPatientIds.first()
                modelPatientId != null && modelPatientId in searchedPatientIds -> modelPatientId
                focusPatientId != null -> focusPatientId
                else -> modelPatientId
            }
            if (resolvedPatientId != null) {
                next = next + ("patientId" to resolvedPatientId)
            }
            return next
        }

        val needsPatientId = toolName in setOf(
            "propose_appointment",
            "brief_patient",
            "list_patient_documents",
            "open_patient_document",
            "open_dental_chart",
        )
        if (!needsPatientId) return args

        var next = args
        if (toolName == "propose_reschedule") {
            relativeDateFromUserText(latestUserText)?.let { next = next + ("date" to it) }
            weekdayTokenFromUserText(latestUserText)?.let { next = next + ("date" to it) }
            timeFromUserText(latestUserText)?.let { next = next + ("time" to it) }
            val aboutFocus = Regex("""\b(him|her|them|this patient)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(latestUserText)
            if (aboutFocus && focusPatientId != null) {
                next = next + ("patientId" to focusPatientId)
            }
            return next
        }
        if (toolName == "propose_appointment") {
            relativeDateFromUserText(latestUserText)?.let { next = next + ("date" to it) }
            weekdayTokenFromUserText(latestUserText)?.let { next = next + ("date" to it) }
            timeFromUserText(latestUserText)?.let { next = next + ("time" to it) }
            partOfDayFromUserText(latestUserText)?.let { next = next + ("partOfDay" to it) }
        }
        if (toolName == "open_patient_document" || toolName == "list_patient_documents") {
            documentQueryFromUserText(latestUserText)?.let { q ->
                if ((next["query"] as? String).isNullOrBlank()) {
                    next = next + ("query" to q)
                }
            }
        }

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

    /** Pull a document title/category hint from speech like "open the MRI" / "show x-ray". */
    private fun documentQueryFromUserText(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val patterns = listOf(
            Regex(
                """(?i)\b(?:open|show|view|открой|покажи|och)\s+(?:me\s+)?(?:the\s+|этот\s+|эту\s+)?(.+)$""",
            ),
        )
        for (p in patterns) {
            val m = p.find(t) ?: continue
            var q = m.groupValues[1].trim()
                .replace(Regex("""(?i)\b(document|documents|документ|hujjat|please|пожалуйста)\b"""), "")
                .trim()
            // Drop trailing wake fluff.
            q = q.replace(Regex("""(?i)\b(shifa|hey|hi)\b"""), "").trim()
            if (q.length in 2..48 && !asksForDentalChart(q)) return q
        }
        return null
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

    private fun extractSearchPatientNames(result: Map<String, Any?>): List<String> {
        val matches = result["matches"] as? List<*> ?: return emptyList()
        return matches.mapNotNull { row ->
            val map = row as? Map<*, *> ?: return@mapNotNull null
            (map["patientName"] as? String)?.takeIf { it.isNotBlank() }
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

    /**
     * Open-ended period words → a `date` token resolveDateRange understands:
     * "from tomorrow onwards" → "from tomorrow", "from Monday" → "from monday",
     * "in the future" / "upcoming" / "from now on" → "upcoming".
     */
    private fun openEndedRangeFromUserText(text: String): String? {
        val t = text.lowercase()
        Regex("""\b(?:from|starting|starting from|after)\s+(tomorrow|today|now|monday|tuesday|wednesday|thursday|friday|saturday|sunday|next week)\b""")
            .find(t)
            ?.let { m ->
                val anchor = m.groupValues[1]
                return when {
                    anchor == "now" || anchor == "today" -> if (t.contains("after")) "from tomorrow" else "upcoming"
                    anchor == "next week" -> "from next monday"
                    else -> "from $anchor"
                }
            }
        if (Regex("""\b(tomorrow|today|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\s+onwards?\b""").find(t) != null) {
            val anchor = Regex("""\b(tomorrow|today|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\s+onwards?\b""")
                .find(t)!!.groupValues[1]
            return if (anchor == "today") "upcoming" else "from $anchor"
        }
        val upcoming = Regex(
            """\b(in (the )?future|upcoming|future|from now on|going forward|coming up|onwards?|ahead|""" +
                """в будущем|предстоящ|kelajakda|bo'lajak)\b""",
        ).containsMatchIn(t)
        return if (upcoming) "upcoming" else null
    }

    /** Pass weekday phrases through so resolveDateArg maps them — never trust model ISO for weekdays. */
    private fun weekdayTokenFromUserText(text: String): String? {
        val t = text.lowercase()
        // Prefer "end of next week" over bare "next week" (Monday).
        if (Regex("""\bend of (the )?next week\b""").containsMatchIn(t) ||
            Regex("""\bend of next week\b""").containsMatchIn(t) ||
            (Regex("""\bend of the week\b""").containsMatchIn(t) &&
                Regex("""\b(next|следующ|keyingi)\b""").containsMatchIn(t))
        ) {
            return "end of next week"
        }
        val nextWeek = Regex("""\b(next week|следующ|keyingi hafta)\b""").containsMatchIn(t)
        val thisWeek = Regex("""\b(this week|этой неделе|shu hafta)\b""").containsMatchIn(t)
        val next = Regex("""\bnext\b""").containsMatchIn(t) || nextWeek
        val day = when {
            Regex("""\b(monday|понедельник|dushanba)\b""").containsMatchIn(t) -> "monday"
            Regex("""\b(tuesday|вторник|seshanba)\b""").containsMatchIn(t) -> "tuesday"
            Regex("""\b(wednesday|среда|chorshanba)\b""").containsMatchIn(t) -> "wednesday"
            Regex("""\b(thursday|четверг|payshanba)\b""").containsMatchIn(t) -> "thursday"
            Regex("""\b(friday|пятница|juma)\b""").containsMatchIn(t) -> "friday"
            Regex("""\b(saturday|суббота|shanba)\b""").containsMatchIn(t) -> "saturday"
            Regex("""\b(sunday|воскресенье|yakshanba)\b""").containsMatchIn(t) -> "sunday"
            nextWeek -> "next week"
            thisWeek -> "this week"
            else -> null
        } ?: return null
        return when {
            day == "next week" -> "next week"
            day == "this week" -> "this week"
            next || nextWeek -> "next $day"
            else -> day
        }
    }

    /**
     * Period, status, documentation, or payment questions that are not today's
     * schedule card and not a single patient's this-week or next-week list.
     */
    private fun asksForAppointmentAnalytics(text: String): Boolean {
        val t = text.lowercase()
        if (asksForFreeSlots(t)) return false
        val aboutCare = Regex("""appointment|visit|booking|qabul|учрашув|запис|collected|unpaid|refund""").containsMatchIn(t)
        val cue = Regex(
            """yesterday|last week|next week|last month|next month|this month|last year|this quarter|""" +
                """last \d+|next \d+|past |previous |cancelled|canceled|no-show|no show|undocumented|unsigned|""" +
                """finalized|unpaid|partially|refund|compare|each day|by status|reschedul|how many are|what about|show me the""",
        ).containsMatchIn(t)
        if (aboutCare && cue) return true
        return Regex("""\b(how many are|what about|show me the|which of them)\b""").containsMatchIn(t)
    }

    /** "How many appointments with him next week" — not today's clinic schedule. */
    private fun asksForPatientAppointments(text: String): Boolean {
        val t = text.lowercase()
        val aboutPatient = Regex(
            """\b(with him|with her|with them|with this patient|for this patient|for him|for her|""" +
                """с ним|с ней|у него|у неё|у нее|bilan|unga)\b""",
        ).containsMatchIn(t) ||
            Regex("""\b(his|her|their)\s+(appointments?|visits?|bookings?)\b""").containsMatchIn(t)
        val aboutCountOrList = Regex(
            """\b(how many|how much|which|list|show|appointments?|visits?|bookings?|""" +
                """сколько|какие|учрашув|qabul)\b""",
        ).containsMatchIn(t)
        val period = Regex(
            """\b(next week|this week|tomorrow|today|monday|tuesday|wednesday|thursday|friday|""" +
                """следующ|этой неделе|keyingi hafta|shu hafta)\b""",
        ).containsMatchIn(t)
        // Explicit patient+period appointment question
        if (aboutPatient && aboutCountOrList) return true
        if (aboutPatient && period && Regex("""\b(appointments?|visits?|bookings?)\b""").containsMatchIn(t)) {
            return true
        }
        return false
    }

    private fun partOfDayFromUserText(text: String): String? {
        val t = text.lowercase()
        return when {
            Regex("""\b(morning|утро|ertalab)\b""").containsMatchIn(t) -> "morning"
            Regex("""\b(afternoon|после обеда|днём|днем|tushdan)\b""").containsMatchIn(t) -> "afternoon"
            Regex("""\b(evening|вечер|kechqurun)\b""").containsMatchIn(t) -> "evening"
            else -> null
        }
    }

    companion object {
        /**
         * Gemini 3 thinking often spends a small max_tokens budget, leaving a stub
         * like "Bu hafta 4" instead of the appointment list.
         */
        internal fun spokenTranslationUsable(source: String, translated: String): Boolean {
            val t = translated.trim()
            val s = source.trim()
            if (t.isEmpty()) return false
            if (s.length <= 24) return t.length >= 2
            if (t.length < 24 && s.length >= 48) return false
            if (s.length > 60 && t.length * 4 < s.length) return false
            return true
        }
    }
}
