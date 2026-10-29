package com.shifa.admin.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.shifa.documents.api.PatientDocumentDto
import com.shifa.patients.api.PatientFormDto
import java.util.UUID

data class DeletedPatientSearchRequest(
    val phone: String? = null,
    val email: String? = null,
    val userId: Long? = null,
)

data class DeletedPatientMatchDto(
    val userId: Long,
    val patientProfileId: Long,
    val deletedAt: String?,
    val matchedBy: String,
    val maskedPhone: String?,
    val maskedEmail: String?,
)

data class DeletedPatientSearchResponse(
    val matches: List<DeletedPatientMatchDto>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DeletedPatientExportDto(
    val userId: Long,
    val patientProfileId: Long,
    val deletedAt: String?,
    val deletionRequestedAt: String?,
    val patientProfile: PatientProfileExportDto,
    val appointments: List<AppointmentExportDto>,
    val documents: List<PatientDocumentDto>,
    val forms: List<PatientFormDto>,
    val consultationNotes: List<ConsultationNoteExportDto>,
    val aiDraftNotes: List<AiDraftNoteExportDto>,
)

data class PatientProfileExportDto(
    val id: Long,
    val fullName: String,
    val birthDate: String?,
    val language: String?,
    val timeZone: String?,
    val chronicDisease: String?,
    val address: String?,
    val photoUrl: String?,
)

data class AppointmentExportDto(
    val id: Long,
    val doctorId: Long,
    val doctorName: String,
    val startAt: String,
    val endAt: String,
    val location: String,
    val reason: String?,
    val status: String,
    val signatureRequested: Boolean,
    val patientSignedAt: String?,
)

data class ConsultationNoteExportDto(
    val id: Long?,
    val doctorId: Long,
    val patientId: Long,
    val appointmentId: Long?,
    val aiDraftNoteId: UUID?,
    val subjective: String?,
    val assessment: String?,
    val plan: String?,
    val body: String?,
    val createdAt: String,
    val source: String,
)

data class AiDraftNoteExportDto(
    val id: UUID?,
    val doctorId: Long,
    val patientId: Long?,
    val consultationId: Long?,
    val aiLabel: String,
    val aiResponseText: String,
    val icdSuggestionsJson: String?,
    val createdAt: String,
    val status: String,
    val modelVersion: String,
    val promptVersion: String,
)
