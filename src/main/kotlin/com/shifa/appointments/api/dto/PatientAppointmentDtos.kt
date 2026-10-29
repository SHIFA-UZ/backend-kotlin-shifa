package com.shifa.appointments.api.dto

/** Response when the patient has already submitted a review for this appointment. */
data class AppointmentReviewDto(
    val rating: Int,
    val comment: String?,
    val createdAt: String
)

data class GenerateVisitSummaryRequest(
    val language: String? = null,
    val force: Boolean? = false
)

data class AskVisitSummaryRequest(
    val language: String? = null,
    val question: String
)
