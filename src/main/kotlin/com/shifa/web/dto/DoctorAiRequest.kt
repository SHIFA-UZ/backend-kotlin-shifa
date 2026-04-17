package com.shifa.web.dto

import com.shifa.ai.OutputLanguage
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class AiMessageDto(
    @get:NotBlank(message = "Message role must not be empty")
    val role: String,
    @get:NotBlank(message = "Message content must not be empty")
    @get:Size(max = 4000, message = "Message content must not exceed 4000 characters")
    val content: String
)

data class DoctorAiRequest(
    val patientId: Long?,
    val consultationId: Long? = null,
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
