package com.shifa.web.dto

import com.shifa.ai.OutputLanguage
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Patient app co-pilot chat (same message shape as doctor AI, without consultation/patient-id fields).
 */
data class PatientCopilotAiRequest(
    @get:Size(max = 4000, message = "Question must not exceed 4000 characters")
    val question: String? = null,
    val messages: List<AiMessageDto>? = null,
    val language: OutputLanguage
) {
    fun resolvedMessages(): List<AiMessageDto> {
        val cleaned = messages
            ?.mapNotNull { msg ->
                val role = msg.role.trim().lowercase()
                val content = msg.content.trim()
                if (content.isBlank()) return@mapNotNull null
                if (role != "user" && role != "assistant" && role != "system") return@mapNotNull null
                AiMessageDto(role = role, content = content)
            }
            ?: emptyList()
        if (cleaned.isNotEmpty()) return cleaned

        val fallbackQuestion = question?.trim().orEmpty()
        if (fallbackQuestion.isBlank()) {
            throw IllegalArgumentException("Question or messages must be provided")
        }
        return listOf(AiMessageDto(role = "user", content = fallbackQuestion))
    }
}

data class PatientCopilotSuggestDoctorsRequest(
    @get:NotBlank(message = "symptomsText is required")
    @get:Size(max = 4000, message = "symptomsText must not exceed 4000 characters")
    val symptomsText: String
)

/**
 * Chat-history driven doctor suggestion: the server uses OpenAI to infer the medical specialty and routing
 * context from the full conversation, then ranks doctors by rating, next available slot, and distance.
 */
data class PatientCopilotSuggestFromChatRequest(
    val messages: List<AiMessageDto>,
    val language: OutputLanguage
)

/**
 * Server-side auto-book: picks the nearest available slot to [preferredStartAt] (ISO-8601 UTC).
 */
data class PatientCopilotBookAppointmentRequest(
    val doctorId: Long,
    /** Preferred start time in UTC; the booked slot may differ to the closest bookable slot. */
    @field:NotBlank(message = "preferredStartAt is required")
    val preferredStartAt: String,
    val isVideo: Boolean = false,
    val reason: String? = null,
    /** Must be true: patient explicitly allows Shifa to book on their behalf. */
    val consentConfirmed: Boolean = false
)
