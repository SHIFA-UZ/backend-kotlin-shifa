package com.shifa.ai

import java.time.LocalDate

data class PatientAiContext(
    val patientId: Long,
    val age: Int?,
    val language: String?,
    val documentSummaries: List<DocumentSummary>,
    val appointmentSummaries: List<AppointmentSummary>
)

data class DocumentSummary(
    val title: String,
    val date: LocalDate
)

data class AppointmentSummary(
    val date: LocalDate,
    val reason: String?
)