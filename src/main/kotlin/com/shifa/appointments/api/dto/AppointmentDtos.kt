package com.shifa.appointments.api.dto


/** Consultation notes for this appointment (e.g. saved Shifa AI drafts). Shown in appointment documentation with
 * "From Shifa AI" badge when source == AI_DRAFT. */
data class ConsultationNoteDto(
    val id: Long,
    val body: String?,
    val subjective: String?,
    val assessment: String?,
    val plan: String?,
    val source: String,
    val createdAt: String
)

data class PutConsultationNotesRequest(
    val body: String? = null,
)

/** Pending AI draft notes for this appointment (e.g. AI Scribe). Doctor can confirm or discard from the
 * appointment screen. */
data class IcdSuggestionDto(
    val code: String,
    val title: String,
    val confidence: Double? = null,
    val isTop: Boolean = false
)

data class DraftNoteDto(
    val id: String,
    val aiLabel: String,
    val body: String,
    val createdAt: String,
    val icdSuggestions: List<IcdSuggestionDto> = emptyList()
)

/** startAt: ISO 8601 UTC. */
data class ChangeSlotReq(
    val startAt: String,
    val slotMinutes: Int,
    /** Clinic of the free slot. A move can land at a different location than the original visit. */
    val locationId: Long? = null,
)

data class CompleteAppointmentRequest(
    val clinicId: Long? = null,
    val visitCharges: List<VisitChargeLineReq>? = null,
    val doctorNotes: String? = null,
    val awaitingScribe: Boolean? = null,
)

data class VisitChargeLineReq(
    val catalogItemId: Long,
    val quantity: Int = 1,
    val unitPriceMinor: Long? = null,
)

data class CompleteAppointmentResponse(
    val status: String,
    val scribePending: Boolean,
    val hasDocumentation: Boolean,
    val hasScribeNote: Boolean,
)

data class ScribeStatusResponse(
    val status: String,
    val hasDocumentation: Boolean,
    val hasScribeNote: Boolean,
)

/** `POST /appointments/{id}/cancel`: why the visit is cancelled; the patient sees the reason and the note. */
data class CancelAppointmentRequest(
    @field:jakarta.validation.constraints.NotNull
    @field:jakarta.validation.constraints.Pattern(regexp = "DOCTOR_UNAVAILABLE|PATIENT_REQUEST|RESCHEDULED|OTHER")
    val reasonCode: String? = null,
    @field:jakarta.validation.constraints.Size(max = 500)
    val note: String? = null,
)

/** `POST /appointments/{id}/no-show`: [notify] false marks the visit without telling the patient (default true). */
data class NoShowRequest(
    val notify: Boolean? = null,
)
