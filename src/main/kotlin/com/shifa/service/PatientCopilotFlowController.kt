package com.shifa.service

import com.shifa.ai.RedFlagEngine
import com.shifa.web.dto.AiMessageDto
import org.springframework.stereotype.Service

@Service
class PatientCopilotFlowController {
    enum class FlowType {
        SYMPTOM_ANALYSIS,
        DOCTOR_SEARCH,
        APPOINTMENT_BOOKING,
        DOCUMENT_QA,
        GENERAL_QA,
        URGENT_CARE
    }

    data class FlowStep(
        val name: String,
        val tool: String?,
        val requiredInputs: List<String> = emptyList(),
        val correctiveQuestion: String? = null
    )

    data class FlowDefinition(
        val flowType: FlowType,
        val orderedSteps: List<FlowStep>
    ) {
        fun toolToStep(tool: String): FlowStep? = orderedSteps.firstOrNull { it.tool == tool }
    }

    data class FlowDecision(
        val flowType: FlowType,
        val requiredInputs: Map<String, String?>,
        val missingInputs: List<String>,
        val allowedTools: Set<String>,
        val requiredSteps: List<String>,
        val explainWhy: String,
        val explainNext: String,
        val definition: FlowDefinition
    )

    data class FlowState(
        val currentFlow: FlowType,
        var currentStep: String? = null,
        var nextStep: String? = null,
        val stepHistory: MutableList<String> = mutableListOf(),
        val completedSteps: MutableSet<String> = linkedSetOf(),
        val missingInputs: MutableSet<String> = linkedSetOf()
    )

    fun definitionFor(flow: FlowType): FlowDefinition = when (flow) {
        FlowType.APPOINTMENT_BOOKING -> FlowDefinition(
            flowType = flow,
            orderedSteps = listOf(
                FlowStep(
                    name = "identify_doctor",
                    tool = "find_doctors",
                    requiredInputs = listOf("symptoms"),
                    correctiveQuestion = "Which doctor would you like to see? I can suggest matching specialists if you describe your symptoms."
                ),
                FlowStep(
                    name = "identify_time",
                    tool = null,
                    requiredInputs = listOf("time"),
                    correctiveQuestion = "When would you like the appointment? Please share a date and time."
                ),
                FlowStep(
                    name = "check_availability",
                    tool = "get_doctor_availability",
                    requiredInputs = listOf("doctorId"),
                    correctiveQuestion = "Let me check the chosen doctor's availability before booking."
                ),
                FlowStep(
                    name = "book_appointment",
                    tool = "book_appointment",
                    requiredInputs = listOf("doctorId", "preferredStartAtUtc", "consentConfirmed"),
                    correctiveQuestion = "Booking requires the chosen doctor, the time, and your explicit consent."
                )
            )
        )
        FlowType.DOCTOR_SEARCH -> FlowDefinition(
            flowType = flow,
            orderedSteps = listOf(
                FlowStep("understand_symptoms", "get_patient_context", emptyList(),
                    "Could you describe what symptoms you are experiencing?"),
                FlowStep("find_doctor_candidates", "find_doctors", listOf("symptoms"), null),
                FlowStep("check_availability", "get_doctor_availability", listOf("doctorId"), null)
            )
        )
        FlowType.SYMPTOM_ANALYSIS -> FlowDefinition(
            flowType = flow,
            orderedSteps = listOf(
                FlowStep("understand_context", "get_patient_context", emptyList(), null),
                FlowStep("suggest_specialists", "find_doctors", listOf("symptoms"),
                    "Could you describe your symptoms in more detail?")
            )
        )
        FlowType.DOCUMENT_QA -> FlowDefinition(
            flowType = flow,
            orderedSteps = listOf(
                FlowStep("retrieve_documents", "get_patient_documents", emptyList(), null),
                FlowStep("review_context", "get_patient_context", emptyList(), null)
            )
        )
        FlowType.GENERAL_QA -> FlowDefinition(
            flowType = flow,
            orderedSteps = listOf(
                FlowStep("review_context", "get_patient_context", emptyList(), null)
            )
        )
        FlowType.URGENT_CARE -> FlowDefinition(flowType = flow, orderedSteps = emptyList())
    }

    fun getNextRequiredStep(definition: FlowDefinition, state: FlowState): FlowStep? {
        return definition.orderedSteps.firstOrNull { it.name !in state.completedSteps }
    }

    fun decideFlow(messages: List<AiMessageDto>, llmFallbackFlow: FlowType? = null): FlowDecision {
        val latestUser = messages.asReversed().firstOrNull { it.role == "user" }?.content.orEmpty()
        val combined = messages.joinToString(" ") { it.content }
        if (RedFlagEngine.analyze(combined).hasEmergency) {
            val def = definitionFor(FlowType.URGENT_CARE)
            return FlowDecision(
                flowType = FlowType.URGENT_CARE,
                requiredInputs = emptyMap(),
                missingInputs = emptyList(),
                allowedTools = emptySet(),
                requiredSteps = emptyList(),
                explainWhy = "Possible emergency indicators were detected.",
                explainNext = "Urgent-care guidance is prioritized over normal booking flow.",
                definition = def
            )
        }

        val ruleFlow = classifyRuleBased(latestUser, combined)
        val flow = ruleFlow ?: llmFallbackFlow ?: FlowType.GENERAL_QA
        val inputs = extractInputs(latestUser, combined)
        val missing = buildMissing(flow, inputs)
        val def = definitionFor(flow)
        return FlowDecision(
            flowType = flow,
            requiredInputs = inputs,
            missingInputs = missing,
            allowedTools = allowedToolsFor(flow),
            requiredSteps = def.orderedSteps.map { it.name },
            explainWhy = "Flow selected based on user intent and required clinical workflow constraints.",
            explainNext = "Execute only allowed deterministic steps for this flow.",
            definition = def
        )
    }

    private fun classifyRuleBased(latestUser: String, combined: String): FlowType? {
        val t = "$latestUser $combined".lowercase()
        if (listOf("book", "appointment", "schedule", "reserve", "tomorrow", "slot").any { t.contains(it) }) {
            return FlowType.APPOINTMENT_BOOKING
        }
        if (listOf("doctor", "specialist", "cardiologist", "dermatologist", "find provider").any { t.contains(it) }) {
            return FlowType.DOCTOR_SEARCH
        }
        if (listOf("report", "lab", "document", "pdf", "scan", "analysis result").any { t.contains(it) }) {
            return FlowType.DOCUMENT_QA
        }
        if (listOf("pain", "fever", "cough", "symptom", "headache", "nausea", "rash").any { t.contains(it) }) {
            return FlowType.SYMPTOM_ANALYSIS
        }
        return null
    }

    private fun extractInputs(latestUser: String, combined: String): Map<String, String?> {
        val low = "$latestUser $combined".lowercase()
        val hasTime = listOf("today", "tomorrow", "am", "pm", "morning", "afternoon", "evening").any { low.contains(it) }
        return mapOf(
            "symptoms" to if (listOf("pain", "fever", "cough", "rash", "nausea", "headache").any { low.contains(it) }) "present" else null,
            "doctorId" to Regex("\\bdoctor\\s*#?(\\d{1,8})\\b").find(low)?.groupValues?.getOrNull(1),
            "time" to if (hasTime) "present" else null,
            "visitType" to if (listOf("video", "online", "in-person", "onsite", "clinic").any { low.contains(it) }) "present" else null
        )
    }

    private fun buildMissing(flow: FlowType, inputs: Map<String, String?>): List<String> {
        val missing = mutableListOf<String>()
        if (flow == FlowType.APPOINTMENT_BOOKING) {
            if (inputs["doctorId"] == null) missing += "doctorId"
            if (inputs["time"] == null) missing += "time"
            if (inputs["visitType"] == null) missing += "visitType"
        }
        if (flow == FlowType.SYMPTOM_ANALYSIS || flow == FlowType.DOCTOR_SEARCH) {
            if (inputs["symptoms"] == null) missing += "symptoms"
        }
        return missing
    }

    private fun allowedToolsFor(flow: FlowType): Set<String> = when (flow) {
        FlowType.SYMPTOM_ANALYSIS -> setOf("get_patient_context", "find_doctors")
        FlowType.DOCTOR_SEARCH -> setOf("get_patient_context", "find_doctors", "get_doctor_availability")
        FlowType.APPOINTMENT_BOOKING -> setOf("find_doctors", "get_doctor_availability", "book_appointment")
        FlowType.DOCUMENT_QA -> setOf("get_patient_documents", "get_patient_context")
        FlowType.GENERAL_QA -> setOf("get_patient_context")
        FlowType.URGENT_CARE -> emptySet()
    }

}

