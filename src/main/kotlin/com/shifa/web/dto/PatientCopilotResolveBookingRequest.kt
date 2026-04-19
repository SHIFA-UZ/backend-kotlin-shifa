package com.shifa.web.dto

import com.shifa.ai.OutputLanguage

/**
 * Ask the model to infer whether the patient wants an immediate auto-book from the conversation.
 * [allowedDoctorIds] when non-empty restricts which doctorId the model may return.
 */
data class PatientCopilotResolveBookingRequest(
    val messages: List<AiMessageDto>,
    val language: OutputLanguage,
    val allowedDoctorIds: List<Long>? = null,
    /**
     * When false or omitted, a valid booking intent returns [needsClientConfirmation] without booking.
     * When true, performs booking after the same validation.
     */
    val confirmAutoBook: Boolean? = null
)
