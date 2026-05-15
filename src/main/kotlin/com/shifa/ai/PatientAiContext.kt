package com.shifa.ai

import java.time.LocalDate

data class PatientAiContext(
    val patientId: Long,
    val age: Int?,
    val language: String?,
    /** Documents where PDF text was not included below (metadata only). */
    val documentSummaries: List<DocumentSummary>,
    val appointmentSummaries: List<AppointmentSummary>,
    /** Extracted PDF text (patient documents this doctor can access); excerpts are truncated. */
    val documentPdfExcerpts: List<DocumentPdfExcerpt> = emptyList(),
    /** Recent saved 025-2 forms (this patient): dental chart + key narrative fields for AI Q&A. */
    val form0252Snapshots: List<Form0252AiSnapshot> = emptyList(),
    /** Consultation notes authored under this doctor–patient pair (excerpts). */
    val consultationNoteSnapshots: List<ConsultationNoteAiSnapshot> = emptyList(),
)

data class DocumentSummary(
    val documentId: Long,
    val title: String,
    val date: LocalDate,
)

data class DocumentPdfExcerpt(
    val documentId: Long,
    val title: String,
    val date: LocalDate,
    val excerpt: String,
)

data class AppointmentSummary(
    val date: LocalDate,
    val reason: String?
)

data class Form0252AiSnapshot(
    val formDate: LocalDate,
    val formNumber: Int?,
    /** Lines like "Tooth 12: extraction" from the dental chart map */
    val dentalChartLines: List<String>,
    /** Labeled free-text fields from the form (truncated in builder) */
    val narrativeLines: List<String>,
    /** Returning-visit rows when present */
    val followUpLines: List<String> = emptyList(),
)

data class ConsultationNoteAiSnapshot(
    val date: LocalDate,
    val excerpt: String,
    val source: String?,
)