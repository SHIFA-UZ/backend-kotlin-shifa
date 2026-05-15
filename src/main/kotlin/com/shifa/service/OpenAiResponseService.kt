package com.shifa.service

import com.shifa.ai.PatientAiContext
import com.shifa.ai.PatientCopilotPromptBuilder
import com.shifa.ai.RedFlagEngine
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.shifa.ai.MedicalPromptBuilder
import com.shifa.ai.OutputLanguage
import com.shifa.config.OpenAiProperties
import com.shifa.domain.DoctorProfile
import com.shifa.domain.PatientProfile
import com.fasterxml.jackson.databind.JsonNode
import com.shifa.web.AiStreamException
import com.shifa.web.dto.AiMessageDto
import com.shifa.web.dto.PatientBookingIntentResolution
import com.shifa.web.dto.PatientCopilotSpecialtyInference
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import com.shifa.service.PatientVisitAskResult

@Service
class OpenAiResponsesService(
    private val props: OpenAiProperties,
    private val copilotToolService: PatientCopilotToolService,
    private val flowController: PatientCopilotFlowController
) {
    data class ToolCallMessage(
        val id: String,
        val name: String,
        val arguments: Map<String, Any?>,
        val rawArguments: String
    )

    data class ToolResultMessage(
        val toolCallId: String,
        val name: String,
        val result: Map<String, Any?>,
        val durationMs: Long,
        val failed: Boolean
    )

    data class AgentLoopOutcome(
        val loopMessages: List<Map<String, Any?>>,
        val progressEvents: List<String>,
        val trace: List<Map<String, Any?>>
    )


    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()
    private val rateLimiter = SimpleRateLimiter(props.maxRequestsPerMinute)

    private val client = OkHttpClient.Builder()
		.connectTimeout(Duration.ofSeconds(10))
		.writeTimeout(Duration.ofSeconds(10))
		.readTimeout(Duration.ZERO) // 🔴 REQUIRED for SSE (infinite stream)
		.build()

    /** Client for non-streaming completion (e.g. briefing) with finite read timeout. */
    private val completionClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .writeTimeout(Duration.ofSeconds(15))
        .readTimeout(Duration.ofSeconds(90))
        .build()


    // Verify OpenAI key injection once at startup (no key material logged)
    @PostConstruct
    fun debugOpenAiKey() {
        log.info(
            "OpenAI key configured: present={}, length={}",
            props.apiKey.isNotBlank(),
            props.apiKey.length
        )
    }

    /**
     * SSE streaming doctor assistant.
     * Deltas are appended and emitted exactly as received from OpenAI; no token-level spacing logic.
     */
    fun streamDoctorAssistant(
        doctor: DoctorProfile,
        patientContext: PatientAiContext?,
        messages: List<AiMessageDto>,
        language: OutputLanguage
    ): Flow<String> = flow {
        val combinedInput = buildString {
            if (patientContext != null) {
                append(patientContext.toString())
                append(" ")
            }
            append(messages.joinToString(" ") { it.content })
        }


		val redFlagResult = RedFlagEngine.analyze(combinedInput)
		if (redFlagResult.hasEmergency) {
			throw AiStreamException(
				code = "SAFETY_BLOCK",
				message = "This may represent a medical emergency. I cannot provide medical advice for this situation. Please seek immediate professional medical care or contact emergency services."
			)
		}

		if (!rateLimiter.tryAcquire()) {
			throw AiStreamException(
				code = "RATE_LIMIT",
				message = "AI rate limit exceeded. Please try again later."
			)
		}
		

// 🧠 Optional patient-aware context (read-only, abstracted)
// 🔐 Build system messages (hard guardrails first)
val systemMessages = mutableListOf<Map<String, String>>(
    mapOf(
        "role" to "system",
        "content" to MedicalPromptBuilder.systemPrompt(doctor, language)
    )
)

// 🧠 Optional patient-aware context (read-only, abstracted)
patientContext?.let { ctx ->
    systemMessages += mapOf(
        "role" to "system",
        "content" to MedicalPromptBuilder.patientContextPrompt(ctx)
    )
}

// 📦 Final payload for Chat Completions API
val payload = mapper.writeValueAsString(
    mapOf(
        "model" to props.model,
        "stream" to true,
        "messages" to systemMessages + messages.map { mapOf("role" to it.role, "content" to it.content) }
    )
)

val request = Request.Builder()
    .url("https://api.openai.com/v1/chat/completions")
    .addHeader("Authorization", "Bearer ${props.apiKey}")
    .addHeader("OpenAI-Project", props.projectId) // ✅ REQUIRED FOR sk-proj keys
    .addHeader("Content-Type", "application/json")
    .addHeader("Accept", "text/event-stream")
    .post(payload.toRequestBody("application/json".toMediaType()))
    .build()


        client.newCall(request).execute().use { response ->

            log.info("OpenAI SSE status={}", response.code)

            if (!response.isSuccessful) {
                throw AiStreamException(
                    code = "AI_UNAVAILABLE",
                    message = "AI is temporarily unavailable. Please try again later."
                )
            }

            val source = response.body?.source() ?: return@use
            val buffer = StringBuilder()

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: continue
                if (!line.startsWith("data:")) continue

                // Do not trim SSE frame payloads; preserve all token spacing.
                val data = line.removePrefix("data:")
                if (data.isBlank() || data.trim() == "[DONE]") continue

                try {
                    val json = mapper.readTree(data)
                    val delta = json.path("choices")
                        .path(0)
                        .path("delta")
                        .path("content")
                        .asText(null)

                    if (delta != null && delta.isNotEmpty()) {
                        buffer.append(delta)
                        emit(delta)
                    }
                } catch (e: Exception) {
                    log.debug("Skipping SSE frame: {}", e.message)
                }
            }
        }
    }

    /**
     * SSE streaming patient co-pilot (Shifa AI in the patient app). Text-only replies; same token streaming as doctor assistant.
     */
    fun streamPatientCopilot(
        patient: PatientProfile,
        messages: List<AiMessageDto>,
        language: OutputLanguage,
        extraContext: String? = null
    ): Flow<String> = flow {
        val recordContext = PatientCopilotPromptBuilder.patientRecordContextPrompt(patient)
        val combinedInput = buildString {
            append(recordContext)
            extraContext?.takeIf { it.isNotBlank() }?.let {
                append(" ")
                append(it)
            }
            append(" ")
            append(messages.joinToString(" ") { it.content })
        }

        val redFlagResult = RedFlagEngine.analyze(combinedInput)
        if (redFlagResult.hasEmergency) {
            throw AiStreamException(
                code = "SAFETY_BLOCK",
                message = "This may represent a medical emergency. I cannot provide medical advice for this situation. Please seek immediate professional medical care or contact emergency services."
            )
        }

        if (!rateLimiter.tryAcquire()) {
            throw AiStreamException(
                code = "RATE_LIMIT",
                message = "AI rate limit exceeded. Please try again later."
            )
        }

        val systemMessages = mutableListOf(
            mapOf(
                "role" to "system",
                "content" to PatientCopilotPromptBuilder.patientCopilotSystemPrompt(language)
            ),
            mapOf(
                "role" to "system",
                "content" to recordContext
            )
        )
        extraContext?.takeIf { it.isNotBlank() }?.let {
            systemMessages += mapOf(
                "role" to "system",
                "content" to it
            )
        }
        val flowDecision = flowController.decideFlow(
            messages = messages,
            llmFallbackFlow = classifyFlowWithLlm(messages, language)
        )
        val flowState = PatientCopilotFlowController.FlowState(
            currentFlow = flowDecision.flowType,
            missingInputs = flowDecision.missingInputs.toMutableSet()
        )
        val firstStep = flowController.getNextRequiredStep(flowDecision.definition, flowState)
        flowState.currentStep = firstStep?.name
        flowState.nextStep = firstStep?.name
        val loopOutcome = runAgentLoop(
            patient = patient,
            language = language,
            messages = messages,
            allowedTools = flowDecision.allowedTools,
            requiredSteps = flowDecision.requiredSteps,
            flowState = flowState,
            definition = flowDecision.definition
        )

        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to props.model,
                "stream" to true,
                "messages" to systemMessages +
                    messages.map { mapOf("role" to it.role, "content" to it.content) } +
                    loopOutcome.loopMessages
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

                log.info("OpenAI patient copilot SSE status={}", response.code)

                if (!response.isSuccessful) {
                    throw AiStreamException(
                        code = "AI_UNAVAILABLE",
                        message = "AI is temporarily unavailable. Please try again later."
                    )
                }

                val source = response.body?.source() ?: return@use

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: continue
                    if (!line.startsWith("data:")) continue

                    val data = line.removePrefix("data:")
                    if (data.isBlank() || data.trim() == "[DONE]") continue

                    try {
                        val json = mapper.readTree(data)
                        val delta = json.path("choices")
                            .path(0)
                            .path("delta")
                            .path("content")
                            .asText(null)

                        if (delta != null && delta.isNotEmpty()) {
                            emit(delta)
                        }
                    } catch (e: Exception) {
                        log.debug("Skipping SSE frame: {}", e.message)
                    }
                }
        }
    }

    private fun runAgentLoop(
        patient: PatientProfile,
        language: OutputLanguage,
        messages: List<AiMessageDto>,
        allowedTools: Set<String>,
        requiredSteps: List<String>,
        flowState: PatientCopilotFlowController.FlowState,
        definition: PatientCopilotFlowController.FlowDefinition
    ): AgentLoopOutcome {
        val loopStart = System.nanoTime()
        val maxIterations = 5
        val timeoutMs = 15_000L
        val loopMessages = mutableListOf<Map<String, Any?>>()
        val progressEvents = mutableListOf<String>()
        val trace = mutableListOf<Map<String, Any?>>()
        val callFingerprints = mutableSetOf<String>()
        val toolMemory = linkedMapOf<String, Any?>()

        for (iteration in 1..maxIterations) {
            val elapsedMs = (System.nanoTime() - loopStart) / 1_000_000
            if (elapsedMs > timeoutMs) {
                trace += mapOf("iteration" to iteration, "status" to "timeout", "elapsedMs" to elapsedMs)
                break
            }
            val modelMsg = requestAgentLoopStep(
                language = language,
                userMessages = messages,
                loopMessages = loopMessages,
                toolMemory = toolMemory,
                allowedTools = allowedTools,
                requiredSteps = requiredSteps,
                flowState = flowState
            ) ?: break
            val toolCallsNode = modelMsg.path("tool_calls")
            if (!toolCallsNode.isArray || toolCallsNode.isEmpty) {
                trace += mapOf("iteration" to iteration, "status" to "final_answer")
                break
            }
            val normalizedCalls = mutableListOf<ToolCallMessage>()
            for (tc in toolCallsNode.take(3)) {
                val id = tc.path("id").asText("").ifBlank { "tc_${iteration}_${normalizedCalls.size}" }
                val name = tc.path("function").path("name").asText("")
                val raw = tc.path("function").path("arguments").asText("{}")
                val args = runCatching { mapper.readValue(raw, Map::class.java) as Map<String, Any?> }.getOrElse { emptyMap() }
                normalizedCalls += ToolCallMessage(id = id, name = name, arguments = args, rawArguments = raw)
            }
            loopMessages += mapOf(
                "role" to "assistant",
                "content" to "",
                "tool_calls" to normalizedCalls.map {
                    mapOf(
                        "id" to it.id,
                        "type" to "function",
                        "function" to mapOf("name" to it.name, "arguments" to it.rawArguments)
                    )
                }
            )

            var anySucceeded = false
            for (tc in normalizedCalls) {
                val fingerprint = "${tc.name}:${mapper.writeValueAsString(tc.arguments)}"
                if (tc.name !in allowedTools) {
                    val msg = mapOf("error" to "tool ${tc.name} is not allowed in flow ${flowState.currentFlow}")
                    loopMessages += toToolResultMessage(ToolResultMessage(tc.id, tc.name, msg, 0, true))
                    trace += mapOf("iteration" to iteration, "tool" to tc.name, "status" to "blocked_not_allowed")
                    log.warn("copilot_flow audit_event=tool_blocked tool={} reason=not_allowed flow={}", tc.name, flowState.currentFlow)
                    continue
                }
                val nextStep = flowController.getNextRequiredStep(definition, flowState)
                val mappedStep = definition.toolToStep(tc.name)
                if (mappedStep != null && nextStep != null && mappedStep.name != nextStep.name) {
                    val correction = nextStep.correctiveQuestion
                        ?: "I need to complete a prior step (${humanizeStepName(nextStep.name)}) before this action."
                    val msg = mapOf(
                        "error" to "STEP_ORDER_VIOLATION",
                        "expectedStep" to nextStep.name,
                        "attemptedTool" to tc.name,
                        "corrective" to correction
                    )
                    loopMessages += toToolResultMessage(ToolResultMessage(tc.id, tc.name, msg, 0, true))
                    loopMessages += mapOf(
                        "role" to "system",
                        "content" to "STEP_ORDER_VIOLATION corrective: $correction"
                    )
                    trace += mapOf(
                        "iteration" to iteration,
                        "tool" to tc.name,
                        "status" to "step_order_violation",
                        "expectedStep" to nextStep.name
                    )
                    log.warn(
                        "copilot_flow audit_event=step_order_violation flow={} attemptedTool={} expectedStep={}",
                        flowState.currentFlow, tc.name, nextStep.name
                    )
                    continue
                }
                if (!callFingerprints.add(fingerprint)) {
                    val msg = mapOf("error" to "repeated identical tool call blocked")
                    loopMessages += toToolResultMessage(ToolResultMessage(tc.id, tc.name, msg, 0, true))
                    trace += mapOf("iteration" to iteration, "tool" to tc.name, "status" to "blocked_repeated")
                    continue
                }
                val validationError = validateToolArguments(tc)
                if (validationError != null) {
                    val msg = mapOf("error" to validationError)
                    loopMessages += toToolResultMessage(ToolResultMessage(tc.id, tc.name, msg, 0, true))
                    trace += mapOf("iteration" to iteration, "tool" to tc.name, "status" to "invalid_args", "reason" to validationError)
                    continue
                }

                progressEvents += progressTextForTool(tc.name)
                val t0 = System.nanoTime()
                var result = executeToolSafely(patient, messages, tc)
                var failed = result["error"] != null
                if (failed) {
                    result = executeToolSafely(patient, messages, tc) // retry once
                    failed = result["error"] != null
                }
                val durationMs = ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1)
                loopMessages += toToolResultMessage(ToolResultMessage(tc.id, tc.name, result, durationMs, failed))
                trace += mapOf(
                    "iteration" to iteration,
                    "tool" to tc.name,
                    "durationMs" to durationMs,
                    "failed" to failed,
                    "failureReason" to result["error"]
                )
                if (!failed) {
                    anySucceeded = true
                    updateToolMemory(toolMemory, tc.name, result)
                    updateFlowStateAfterStep(flowState, tc.name, result)
                    val mapped = definition.toolToStep(tc.name)
                    if (mapped != null && mapped.name !in flowState.completedSteps) {
                        flowState.completedSteps += mapped.name
                        flowState.stepHistory += mapped.name
                        flowState.currentStep = mapped.name
                        log.info(
                            "copilot_flow audit_event=step_transition flow={} step={} history={}",
                            flowState.currentFlow, mapped.name, flowState.stepHistory
                        )
                    }
                    val nextAfter = flowController.getNextRequiredStep(definition, flowState)
                    flowState.nextStep = nextAfter?.name
                    if (nextAfter != null) {
                        val total = definition.orderedSteps.size
                        val idx = definition.orderedSteps.indexOfFirst { it.name == nextAfter.name } + 1
                        progressEvents += "Step $idx/$total: ${humanizeStepName(nextAfter.name)}"
                    }
                    progressEvents += completionTextForTool(tc.name, result)
                }
            }
            if (!anySucceeded) break
            val missingSteps = requiredSteps.filterNot { it in flowState.completedSteps }
            if (missingSteps.isNotEmpty()) {
                flowState.missingInputs += missingSteps
            }
            loopMessages += mapOf(
                "role" to "system",
                "content" to "Flow state: ${mapper.writeValueAsString(flowState)} | Tool memory snapshot: ${mapper.writeValueAsString(toolMemory)}"
            )
        }

        val totalMs = (System.nanoTime() - loopStart) / 1_000_000
        log.info(
            "copilot_agent_loop done iterations={} totalMs={} toolsUsed={} trace={}",
            trace.mapNotNull { it["iteration"] }.distinct().size,
            totalMs,
            trace.mapNotNull { it["tool"] },
            mapper.writeValueAsString(trace)
        )
        return AgentLoopOutcome(
            loopMessages = loopMessages,
            progressEvents = progressEvents.filter { it.isNotBlank() }.distinct().take(6),
            trace = trace
        )
    }

    private fun requestAgentLoopStep(
        language: OutputLanguage,
        userMessages: List<AiMessageDto>,
        loopMessages: List<Map<String, Any?>>,
        toolMemory: Map<String, Any?>,
        allowedTools: Set<String>,
        requiredSteps: List<String>,
        flowState: PatientCopilotFlowController.FlowState
    ): JsonNode? {
        val orchestrationPrompt = """
You are a deterministic medical copilot agent orchestrator.
You may call tools iteratively to complete multi-step tasks (doctor matching, availability, booking).
Only call tools when needed; avoid repeating a previous identical tool call.
Allowed tools for this flow: ${allowedTools.joinToString(", ")}.
Required flow steps in order: ${requiredSteps.joinToString(" -> ")}.
Current flow state: ${mapper.writeValueAsString(flowState)}.
You MUST follow the required flow steps in order. Do not skip steps.
If the next required step's tool prerequisites or inputs are missing, ask the user for that single missing item rather than calling another tool.
If enough information is gathered, stop tool usage and provide a normal assistant reply.
If any tool result includes error, explain issue and suggest a safe fallback.
Language: ${language.isoCode}
""".trimIndent()
        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to props.model,
                "stream" to false,
                "temperature" to 0.0,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to orchestrationPrompt),
                    mapOf("role" to "system", "content" to "Known tool memory: ${mapper.writeValueAsString(toolMemory)}")
                ) + userMessages.map { mapOf("role" to it.role, "content" to it.content) } + loopMessages,
                "tools" to patientCopilotToolsSchema(),
                "tool_choice" to "auto"
            )
        )
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${props.apiKey}")
            .addHeader("OpenAI-Project", props.projectId)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            withRetry(maxAttempts = 2) {
                completionClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withRetry null
                    val body = response.body?.string() ?: return@withRetry null
                    mapper.readTree(body).path("choices").path(0).path("message")
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun executeToolSafely(
        patient: PatientProfile,
        messages: List<AiMessageDto>,
        tc: ToolCallMessage
    ): Map<String, Any?> {
        return try {
            copilotToolService.execute(
                patient = patient,
                messages = messages,
                toolCall = PatientCopilotToolService.ToolCall(name = tc.name, arguments = tc.arguments)
            )
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: "tool_execution_failed"))
        }
    }

    private fun validateToolArguments(tc: ToolCallMessage): String? {
        return when (tc.name) {
            "get_doctor_availability" -> if ((tc.arguments["doctorId"] as? Number)?.toLong() == null) "doctorId is required" else null
            "book_appointment" -> {
                when {
                    (tc.arguments["doctorId"] as? Number)?.toLong() == null -> "doctorId is required"
                    (tc.arguments["preferredStartAtUtc"] as? String).isNullOrBlank() -> "preferredStartAtUtc is required"
                    (tc.arguments["consentConfirmed"] as? Boolean) != true -> "consentConfirmed=true required"
                    runCatching { java.time.Instant.parse(tc.arguments["preferredStartAtUtc"] as String) }.isFailure -> "preferredStartAtUtc must be ISO-8601 UTC"
                    runCatching { java.time.Instant.parse(tc.arguments["preferredStartAtUtc"] as String).isBefore(java.time.Instant.now()) }.getOrDefault(false) -> "preferredStartAtUtc must be in the future"
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun humanizeStepName(name: String): String = when (name) {
        "identify_doctor" -> "Selecting doctor"
        "identify_time" -> "Choosing time"
        "check_availability" -> "Checking availability"
        "book_appointment" -> "Booking appointment"
        "understand_symptoms", "understand_context" -> "Understanding symptoms"
        "find_doctor_candidates" -> "Finding doctors"
        "suggest_specialists" -> "Suggesting specialists"
        "retrieve_documents" -> "Reviewing documents"
        "review_context" -> "Reviewing context"
        else -> name.replace('_', ' ').replaceFirstChar { it.titlecase() }
    }

    private fun toToolResultMessage(result: ToolResultMessage): Map<String, Any?> = mapOf(
        "role" to "tool",
        "tool_call_id" to result.toolCallId,
        "name" to result.name,
        "content" to mapper.writeValueAsString(result.result + mapOf("durationMs" to result.durationMs, "failed" to result.failed))
    )

    private fun updateToolMemory(memory: MutableMap<String, Any?>, toolName: String, result: Map<String, Any?>) {
        when (toolName) {
            "find_doctors" -> memory["doctorsFound"] = (result["doctors"] as? List<*>)?.size ?: 0
            "get_doctor_availability" -> {
                memory["selectedDoctor"] = result["doctorId"]
                memory["availableSlots"] = listOfNotNull(result["nextAvailableStartAt"])
            }
            "book_appointment" -> memory["bookingResult"] = result
            "get_patient_context", "get_patient_documents" -> memory[toolName] = result
        }
    }

    private fun updateFlowStateAfterStep(
        state: PatientCopilotFlowController.FlowState,
        toolName: String,
        result: Map<String, Any?>
    ) {
        when (toolName) {
            "find_doctors" -> {
                if (((result["doctors"] as? List<*>)?.isNotEmpty() == true)) {
                    state.completedSteps += "doctor_candidates_found"
                    state.completedSteps += "doctor_identified"
                    state.missingInputs.remove("doctorId")
                }
                state.completedSteps += "symptoms_understood"
                state.missingInputs.remove("symptoms")
            }
            "get_doctor_availability" -> {
                state.completedSteps += "availability_checked"
                if (result["nextAvailableStartAt"] != null) {
                    state.completedSteps += "time_identified"
                    state.missingInputs.remove("time")
                }
            }
            "book_appointment" -> {
                state.completedSteps += "booking_attempted"
            }
            "get_patient_documents" -> state.completedSteps += "documents_retrieved"
        }
    }

    private fun classifyFlowWithLlm(
        messages: List<AiMessageDto>,
        language: OutputLanguage
    ): PatientCopilotFlowController.FlowType? {
        val transcript = mapper.writeValueAsString(
            mapOf("messages" to messages.map { mapOf("role" to it.role, "content" to it.content) })
        )
        val prompt = """
Classify patient copilot request into exactly one flow:
SYMPTOM_ANALYSIS, DOCTOR_SEARCH, APPOINTMENT_BOOKING, DOCUMENT_QA, GENERAL_QA.
Return JSON only: {"flowType":"..."}.
Language hint: ${language.isoCode}
""".trimIndent()
        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to props.model,
                "stream" to false,
                "temperature" to 0.0,
                "response_format" to mapOf("type" to "json_object"),
                "messages" to listOf(
                    mapOf("role" to "system", "content" to prompt),
                    mapOf("role" to "user", "content" to transcript)
                )
            )
        )
        val req = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${props.apiKey}")
            .addHeader("OpenAI-Project", props.projectId)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            withRetry(maxAttempts = 2) {
                completionClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withRetry null
                    val body = resp.body?.string() ?: return@withRetry null
                    val content = mapper.readTree(body).path("choices").path(0).path("message").path("content").asText("")
                    if (content.isBlank()) return@withRetry null
                    val flow = mapper.readTree(content).path("flowType").asText("").trim()
                    runCatching { PatientCopilotFlowController.FlowType.valueOf(flow) }.getOrNull()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun progressTextForTool(toolName: String): String = when (toolName) {
        "find_doctors" -> "Looking for doctors..."
        "get_doctor_availability" -> "Checking availability..."
        "book_appointment" -> "Preparing booking..."
        "get_patient_documents" -> "Reviewing your documents..."
        "get_patient_context" -> "Reviewing your health context..."
        else -> "Working on your request..."
    }

    private fun completionTextForTool(toolName: String, result: Map<String, Any?>): String = when (toolName) {
        "find_doctors" -> "Found ${((result["doctors"] as? List<*>)?.size ?: 0)} options."
        "get_doctor_availability" -> if (result["nextAvailableStartAt"] != null) "Availability checked." else "No upcoming availability found yet."
        "book_appointment" -> if (result["booked"] == true) "Appointment booked." else "Could not book yet."
        else -> ""
    }

    private fun patientCopilotToolsSchema(): List<Map<String, Any?>> = listOf(
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "get_patient_context",
                "description" to "Get patient medical context: conditions, complaints, medications, appointments.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "intent" to mapOf("type" to "string", "enum" to listOf("documents", "symptoms", "appointments", "general"))
                    ),
                    "required" to emptyList<String>()
                )
            )
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "get_patient_documents",
                "description" to "Get patient documents metadata and optional extracted snippets.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "includeSnippets" to mapOf("type" to "boolean"),
                        "limit" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 12)
                    )
                )
            )
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "find_doctors",
                "description" to "Find and rank doctors by specialty match, availability, rating, and distance.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "specialties" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                        "symptoms" to mapOf("type" to "array", "items" to mapOf("type" to "string"))
                    )
                )
            )
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "get_doctor_availability",
                "description" to "Get next available slot for a doctor.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "doctorId" to mapOf("type" to "integer")
                    ),
                    "required" to listOf("doctorId")
                )
            )
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "book_appointment",
                "description" to "Book an appointment after explicit consent.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "doctorId" to mapOf("type" to "integer"),
                        "preferredStartAtUtc" to mapOf("type" to "string"),
                        "isVideo" to mapOf("type" to "boolean"),
                        "consentConfirmed" to mapOf("type" to "boolean")
                    ),
                    "required" to listOf("doctorId", "preferredStartAtUtc", "consentConfirmed")
                )
            )
        )
    )

    /**
     * Non-streaming JSON extraction: whether the patient clearly asked to auto-book with consent,
     * doctor, preferred time (interpreted in [patientTimeZone]), and video vs onsite.
     */
    fun resolvePatientBookingIntent(
        messages: List<AiMessageDto>,
        language: OutputLanguage,
        patientTimeZone: String?,
        allowedDoctorIds: List<Long>?
    ): PatientBookingIntentResolution {
        val combined = messages.joinToString(" ") { it.content }
        if (RedFlagEngine.analyze(combined).hasEmergency) {
            return PatientBookingIntentResolution()
        }
        if (!rateLimiter.tryAcquire()) {
            return PatientBookingIntentResolution()
        }

        val tz = patientTimeZone?.trim()?.takeIf { it.isNotBlank() } ?: "UTC"
        val allowedLine = if (!allowedDoctorIds.isNullOrEmpty()) {
            "doctorId MUST be one of these numeric ids only: ${allowedDoctorIds.joinToString(", ")}. If none match the patient's chosen doctor, return doctorId null and bookNow false."
        } else {
            "Return doctorId only if the transcript clearly and uniquely identifies one doctor; otherwise null and bookNow false."
        }

        val systemPrompt = """
You extract structured booking intent from a Shifa patient AI chat. The user message is a JSON string whose key "messages" is an array of {role, content} in order.

Return ONLY a JSON object with exactly these keys:
bookNow (boolean), doctorId (number or null), preferredStartAtUtc (string ISO-8601 instant in UTC with Z suffix, or null), isVideo (boolean or null), userExplicitConsentToAutoBook (boolean).

STRICT RULES:
1. userExplicitConsentToAutoBook true ONLY if a message with role "user" contains clear authorization to automatically book on their behalf (e.g. "yes book it for me", "please schedule that", "go ahead and book", "confirm auto-booking"). A vague "ok" or "yes" to a general question is NOT sufficient.
2. bookNow true ONLY if userExplicitConsentToAutoBook is true AND doctorId is non-null AND preferredStartAtUtc is non-null AND isVideo is non-null AND they refer to the same booking request.
3. $allowedLine
4. Interpret the patient's stated date and time in IANA time zone "$tz", then set preferredStartAtUtc to the correct UTC instant. If the patient only said a date, assume a reasonable time they mentioned or midday local only if they implied "any time"; otherwise prefer null and bookNow false.
5. Use the MOST RECENT user booking details in the transcript. If older and newer date/time or doctor details conflict, use the latest explicit user instruction and ignore older turns.
6. preferredStartAtUtc MUST be in the future relative to now; if the transcript points to a past time/date or unclear year/date, set preferredStartAtUtc null and bookNow false.
7. Never infer doctorId from assistant suggestions alone. Only set doctorId if the user explicitly picked a doctor or there is exactly one unambiguous doctor choice in user messages.
8. isVideo: true for video/online/remote; false for clinic/in-person/onsite.
9. Use assistant messages only as context; consent must be inferred from user messages.
10. If anything is ambiguous or multiple doctors, return bookNow false.

Language hint for understanding user text: ${language.name}
""".trimIndent()

        val transcript = mapper.writeValueAsString(
            mapOf("messages" to messages.map { mapOf("role" to it.role, "content" to it.content) })
        )

        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to props.model,
                "stream" to false,
                "temperature" to 0.1,
                "max_tokens" to 450,
                "response_format" to mapOf("type" to "json_object"),
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to transcript)
                )
            )
        )

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${props.apiKey}")
            .addHeader("OpenAI-Project", props.projectId)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val parsed = withRetry {
            completionClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    log.warn("OpenAI booking intent resolution failed: {}", response.code)
                    return@withRetry PatientBookingIntentResolution()
                }
                val body = response.body?.string() ?: return@withRetry PatientBookingIntentResolution()
                val tree = mapper.readTree(body)
                val content = tree.path("choices").path(0).path("message").path("content").asText("").trim()
                if (content.isBlank()) return@withRetry PatientBookingIntentResolution()
                parseBookingIntentJson(content)
            }
        }
        return parsed
    }

    /**
     * Infer which medical specialty (or specialties) best match the patient's chat so far and whether the copilot
     * already has enough clinical context to recommend doctors. When info is insufficient, returns a single concise
     * clarifying question in the patient's language so the copilot can ask for the missing piece.
     */
    fun inferSpecialtiesAndClarification(
        messages: List<AiMessageDto>,
        language: OutputLanguage
    ): PatientCopilotSpecialtyInference {
        val combined = messages.joinToString(" ") { it.content }
        if (combined.isBlank()) return PatientCopilotSpecialtyInference()
        if (RedFlagEngine.analyze(combined).hasEmergency) return PatientCopilotSpecialtyInference()
        if (!rateLimiter.tryAcquire()) return PatientCopilotSpecialtyInference()

        val systemPrompt = """
You help Shifa route a patient to the right medical specialist based on their chat so far.
You receive a JSON string whose key "messages" is an ordered array of {role, content}.

Return ONLY a JSON object with exactly these keys:
specialties (array of short lowercase strings), searchTerms (array of short lowercase strings),
hasEnoughInfo (boolean), clarifyingQuestion (string or null).

RULES:
1. specialties: 1 to 3 medical specialty labels that would treat the patient's likely issue, as short English lowercase nouns that match a doctor's profession field (e.g. "cardiologist", "dermatologist", "gastroenterologist", "pediatrician", "neurologist", "urologist", "gynecologist", "otolaryngologist", "endocrinologist", "orthopedist", "psychiatrist", "general practitioner", "dentist"). If multiple specialties are plausible, put the most likely first.
2. searchTerms: 2-6 short lowercase keywords (symptom name, affected organ, condition) that could be used as a fallback text search if specialty matching fails (e.g. ["chest pain", "shortness of breath"]). Use English clinical terms even if the patient wrote in another language.
3. hasEnoughInfo: true ONLY if the patient has described at least a concrete symptom or body system AND you are confident which specialty to suggest. If the chat is still generic (e.g. "I feel bad", "need a doctor") set false.
4. clarifyingQuestion: when hasEnoughInfo is false, return ONE short friendly question in ${language.name} (${language.isoCode}) asking for the single most useful missing piece (symptom detail, duration/severity, location on body, preferred date/time, or onsite vs video). When hasEnoughInfo is true, set clarifyingQuestion to null.
5. Never invent specialties; pick from common medical specialties only. Prefer "general practitioner" when the issue is vague but clearly medical.
6. Output must be valid JSON only; no prose, no Markdown.
""".trimIndent()

        val transcript = mapper.writeValueAsString(
            mapOf("messages" to messages.map { mapOf("role" to it.role, "content" to it.content) })
        )

        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to props.model,
                "stream" to false,
                "temperature" to 0.1,
                "max_tokens" to 350,
                "response_format" to mapOf("type" to "json_object"),
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to transcript)
                )
            )
        )

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${props.apiKey}")
            .addHeader("OpenAI-Project", props.projectId)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            withRetry {
                completionClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        log.warn("OpenAI specialty inference failed: {}", response.code)
                        return@withRetry PatientCopilotSpecialtyInference()
                    }
                    val body = response.body?.string() ?: return@withRetry PatientCopilotSpecialtyInference()
                    val tree = mapper.readTree(body)
                    val content = tree.path("choices").path(0).path("message").path("content").asText("").trim()
                    if (content.isBlank()) return@withRetry PatientCopilotSpecialtyInference()
                    val n = mapper.readTree(content)
                    val specialties = n.path("specialties")
                        .takeIf { it.isArray }
                        ?.mapNotNull { it.asText("").trim().lowercase().ifBlank { null } }
                        ?.distinct()
                        ?: emptyList()
                    val searchTerms = n.path("searchTerms")
                        .takeIf { it.isArray }
                        ?.mapNotNull { it.asText("").trim().lowercase().ifBlank { null } }
                        ?.distinct()
                        ?: emptyList()
                    val hasEnough = jsonBool(n.path("hasEnoughInfo"))
                    val clarify = n.path("clarifyingQuestion").asText("").trim().ifBlank { null }
                    PatientCopilotSpecialtyInference(
                        specialties = specialties,
                        searchTerms = searchTerms,
                        hasEnoughInfo = hasEnough,
                        clarifyingQuestion = if (hasEnough) null else clarify
                    )
                }
            }
        } catch (e: Exception) {
            log.debug("Specialty inference error: {}", e.message)
            PatientCopilotSpecialtyInference()
        }
    }

    private fun <T> withRetry(maxAttempts: Int = 3, block: () -> T): T {
        var last: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                last = e
                if (attempt < maxAttempts - 1) {
                    try {
                        Thread.sleep((250L * (attempt + 1)))
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }
        throw (last ?: IllegalStateException("Unknown retry error"))
    }

    private fun parseBookingIntentJson(content: String): PatientBookingIntentResolution {
        return try {
            val n = mapper.readTree(content)
            PatientBookingIntentResolution(
                bookNow = jsonBool(n.path("bookNow")),
                doctorId = jsonLong(n.path("doctorId")),
                preferredStartAtUtc = n.path("preferredStartAtUtc").asText("").trim().ifBlank { null },
                isVideo = jsonBoolOrNull(n.path("isVideo")),
                userExplicitConsentToAutoBook = jsonBool(n.path("userExplicitConsentToAutoBook"))
            )
        } catch (e: Exception) {
            log.debug("Failed to parse booking intent JSON: {}", e.message)
            PatientBookingIntentResolution()
        }
    }

    private fun jsonBool(node: JsonNode): Boolean = when {
        node.isMissingNode || node.isNull -> false
        node.isBoolean -> node.booleanValue()
        node.isIntegralNumber -> node.asLong() != 0L
        node.isTextual -> node.asText().equals("true", ignoreCase = true)
        else -> false
    }

    private fun jsonLong(node: JsonNode): Long? = when {
        node.isMissingNode || node.isNull -> null
        node.isIntegralNumber -> node.asLong()
        node.isFloatingPointNumber -> node.asDouble().toLong()
        node.isTextual -> node.asText().trim().toLongOrNull()
        else -> null
    }

    private fun jsonBoolOrNull(node: JsonNode): Boolean? = when {
        node.isMissingNode || node.isNull -> null
        node.isBoolean -> node.booleanValue()
        node.isIntegralNumber -> node.asLong() != 0L
        node.isTextual -> when (node.asText().trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> null
    }

    /**
     * Single completion for patient briefing. Context may contain multilingual document text.
     * @param documentContext Patient demographics + appointments + 025-2 + consultation notes + PDF excerpts (may be multilingual).
     * @param outputLanguage Preferred language for the briefing: "en", "uz", "ru", etc.
     */
    fun completeBriefing(documentContext: String, outputLanguage: String): String {
        if (!rateLimiter.tryAcquire()) {
            throw AiStreamException(
                code = "RATE_LIMIT",
                message = "AI rate limit exceeded. Please try again later."
            )
        }
        val systemPrompt = """
You are a medical assistant preparing a concise clinical briefing for a doctor.

RULES:
- Use ONLY the information provided below: (1) appointment history with this doctor (date, reason, status, location),
  (2) saved form 025-2 structured entries including per-tooth dental chart rows and narrative clinical fields,
  (3) consultation notes from visits with this doctor (MANUAL vs AI_DRAFT sources are labeled),
  and (4) extracted PDF document text. Content may be in multiple languages (e.g. Uzbek, Russian, English).
  Read and summarize in whatever language it is written; you may produce the briefing in the requested output language
  or keep key terms in the original language where appropriate.
- Output language requested: $outputLanguage. Write the briefing in this language when possible, but preserve important clinical terms and findings from the sources.
- Summarize key information: appointments, tooth-level findings from 025-2 where present, consultation notes (procedures, assessments, plans), and from PDFs: diagnoses, medications, procedures, and other clinical detail. Be concise (under 400 words).
- Do NOT diagnose, prescribe, or give medical advice. Only summarize what is provided.
- If a section is empty or unreadable, skip it. Do not invent content.
- IMPORTANT: Use plain text only. Do NOT use Markdown formatting (no **bold**, no bullet markers like '-', no numbered lists with '1.', no headings with '#'). Write paragraphs and short lists as simple sentences separated by newlines.
""".trimIndent()
        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to props.model,
                "stream" to false,
                "max_tokens" to 800,
                "temperature" to 0.3,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to "Appointment history and document content:\n\n$documentContext")
                )
            )
        )
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${props.apiKey}")
            .addHeader("OpenAI-Project", props.projectId)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        completionClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                log.warn("OpenAI briefing completion failed: {}", response.code)
                throw AiStreamException(
                    code = "AI_UNAVAILABLE",
                    message = "AI is temporarily unavailable. Please try again later."
                )
            }
            val body = response.body?.string() ?: throw AiStreamException("AI_UNAVAILABLE", "Empty response")
            val json = mapper.readTree(body)
            val content = json.path("choices").path(0).path("message").path("content").asText("")
            return content.trim().ifEmpty { "No summary could be generated." }
        }
    }

    /**
     * Extract diagnosis *terms* from clinical text. MUST NOT return ICD codes.
     * Output is a small list of short terms/phrases, one per line.
     *
     * This is used as a first step for ICD-10 mapping against our local catalog.
     */
    fun extractDiagnosisTerms(text: String, outputLanguage: String = "en"): List<String> {
        if (!rateLimiter.tryAcquire()) {
            throw AiStreamException(
                code = "RATE_LIMIT",
                message = "AI rate limit exceeded. Please try again later."
            )
        }
        val systemPrompt = """
You are a medical assistant helping a doctor code diagnoses.

TASK:
- Read the clinical text and extract the most likely *specific* clinical diagnosis terms ONLY (short phrases).
- Return a list of terms, one per line.

STRICT RULES:
- DO NOT output any ICD-10 codes.
- DO NOT output numbering like "1." and DO NOT use Markdown bullets like "-" or "*".
- Return at most 5 lines.
- Output language: $outputLanguage (but keep clinical terms as written if they are Russian/Uzbek/English).
- If no diagnosis terms are present, return an empty response.

QUALITY RULES:
- Prefer billable/clinically specific diagnoses over vague symptoms.
- Avoid vague terms like: infection, pain, inflammation, fever.
- Prefer: acute bronchitis, type 2 diabetes mellitus, dental caries, pulpitis, periodontitis, etc.
- If only symptoms are present, return the most specific *syndrome/condition* implied, otherwise return empty.
""".trimIndent()

        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to props.model,
                "stream" to false,
                "max_tokens" to 120,
                "temperature" to 0.0,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to text.take(12_000))
                )
            )
        )
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${props.apiKey}")
            .addHeader("OpenAI-Project", props.projectId)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        completionClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                log.warn("OpenAI diagnosis-term extraction failed: {}", response.code)
                throw AiStreamException(
                    code = "AI_UNAVAILABLE",
                    message = "AI is temporarily unavailable. Please try again later."
                )
            }
            val body = response.body?.string() ?: return emptyList()
            val json = mapper.readTree(body)
            val content = json.path("choices").path(0).path("message").path("content").asText("").trim()
            if (content.isBlank()) return emptyList()

            return content
                .lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { line ->
                    // remove common accidental prefixes (still don't allow codes)
                    line.removePrefix("•").removePrefix("-").removePrefix("*").trim()
                }
                .take(5)
        }
    }

    /**
     * Generate patient-facing structured visit summary JSON from doctor-approved note/context.
     * Returns raw JSON string.
     */
    fun generatePatientVisitSummary(
        sourceContext: String,
        outputLanguage: String
    ): String {
        if (!rateLimiter.tryAcquire()) {
            throw AiStreamException(
                code = "RATE_LIMIT",
                message = "AI rate limit exceeded. Please try again later."
            )
        }
        val systemPrompt = """
You are a medical assistant creating a patient-friendly after-visit summary.

OUTPUT REQUIREMENTS:
- Return ONLY valid JSON.
- Use this exact top-level object schema:
  {
    "summaryPlain": string,
    "carePlan": [string],
    "medicationGuidance": [{"name": string, "instructions": string, "missedDose": string}],
    "redFlags": [{"sign": string, "urgency": "emergency"|"urgent"|"routine"}],
    "nextSteps": [string],
    "disclaimer": string
  }
- Output language: $outputLanguage.

SAFETY RULES:
- Use ONLY facts from provided context. Do not invent diagnoses, medicines, doses, or dates.
- If a section has no data, return an empty array for that section.
- Keep patient language simple and concise.
- Do not provide new treatment advice beyond documented plan.
""".trimIndent()

        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to props.model,
                "stream" to false,
                "max_tokens" to 1200,
                "temperature" to 0.2,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to sourceContext)
                )
            )
        )
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${props.apiKey}")
            .addHeader("OpenAI-Project", props.projectId)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        completionClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                log.warn("OpenAI visit-summary generation failed: {}", response.code)
                throw AiStreamException(
                    code = "AI_UNAVAILABLE",
                    message = "AI is temporarily unavailable. Please try again later."
                )
            }
            val body = response.body?.string() ?: throw AiStreamException("AI_UNAVAILABLE", "Empty response")
            val json = mapper.readTree(body)
            val content = json.path("choices").path(0).path("message").path("content").asText("").trim()
            return content
        }
    }

    /**
     * Answer a patient follow-up question using generated visit summary JSON as source of truth.
     */
    fun answerPatientVisitQuestion(
        summaryJson: String,
        question: String,
        outputLanguage: String
    ): PatientVisitAskResult {
        if (!rateLimiter.tryAcquire()) {
            throw AiStreamException(
                code = "RATE_LIMIT",
                message = "AI rate limit exceeded. Please try again later."
            )
        }
        val systemPrompt = """
You answer patient follow-up questions based ONLY on the provided visit summary JSON.

RULES:
- Output language: $outputLanguage.
- Do NOT add new diagnosis or treatment.
- If the answer is not present in summary, say that it is not specified and advise contacting doctor.
- Keep answer concise and plain language.
- Return ONLY valid JSON with this schema:
  {"answer": string, "citations": [string]}
- citations should reference summary sections by keys (e.g. "summaryPlain", "carePlan[0]", "medicationGuidance[1]").
""".trimIndent()

        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to props.model,
                "stream" to false,
                "max_tokens" to 350,
                "temperature" to 0.2,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to "Summary JSON:\n$summaryJson\n\nQuestion:\n$question")
                )
            )
        )
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${props.apiKey}")
            .addHeader("OpenAI-Project", props.projectId)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        completionClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw AiStreamException(
                    code = "AI_UNAVAILABLE",
                    message = "AI is temporarily unavailable. Please try again later."
                )
            }
            val body = response.body?.string() ?: return PatientVisitAskResult("", emptyList())
            val json = mapper.readTree(body)
            val content = json.path("choices").path(0).path("message").path("content").asText("").trim()
            if (content.isBlank()) return PatientVisitAskResult("", emptyList())
            return try {
                val parsed = mapper.readTree(content)
                val answer = parsed.path("answer").asText("").trim()
                val citations = parsed.path("citations")
                    .takeIf { it.isArray }
                    ?.mapNotNull { n -> n.asText("").trim().ifEmpty { null } }
                    ?: emptyList()
                PatientVisitAskResult(answer = answer, citations = citations)
            } catch (_: Exception) {
                // Backward compatibility if model returns plain text.
                PatientVisitAskResult(answer = content, citations = emptyList())
            }
        }
    }
}
