package com.shifa.patients.api.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class PatientDto(
    val id: Long?,
    val name: String,
    val phone: String?,
    val phones: List<String> = emptyList(),
    val email: String?,
    val address: String?, // Legacy field - will be populated from structured location if available
    val birthDate: String?,
    val language: String?,
    val photoUrl: String?,              // absolute URL
    val chronicDisease: String?,
    val hasAccount: Boolean,            // ✅ NEW
    val username: String?,              // ✅ NEW
    val documents: List<DocumentDto>,
    // Structured location fields
    val locationCountry: String? = null,
    val locationRegion: String? = null,
    val locationDistrict: String? = null,
    val locationCity: String? = null,
    val locationPostalCode: String? = null,
    val locationStreetAddress: String? = null,
    /** IANA timezone for remote task schedule (e.g. Europe/Berlin). Doctor enters times in this zone. */
    val timeZone: String? = null,
    /** DevSMS before each future appointment when enabled. */
    val smsReminderEnabled: Boolean = false,
    /** Hours before appointment to send SMS (1 or 24). */
    val smsReminderHoursBefore: Int = 24,
    val gender: String? = null,
    val bloodGroup: String? = null,
    val allergies: String? = null,
    /** ACTIVE | AT_RISK | FOLLOW_UP — computed for roster display. */
    val clinicalStatus: String = "ACTIVE",
    val atRisk: Boolean = false,
    val followUpRequired: Boolean = false,
)

data class DocumentDto(
    val id: Long?,
    val title: String,
    val date: String,
    val url: String?,
    val canView: Boolean = true,
    val creatorLabel: String = "Unknown"
)

/** Minimal DTO for calendar assign-patient: search by id or name, show avatar. */
data class PatientAssignmentDto(val id: Long?, val name: String, val photoUrl: String? = null)

data class CreatePatientRequest(
    @field:NotBlank(message = "Name is required")
    @field:Size(min = 1, max = 255)
    val name: String,
    @field:Size(max = 50)
    val phone: String?,
    val phones: List<@Size(max = 50) String>? = null,
    @field:Email @field:Size(max = 255)
    val email: String?,
    @field:Size(max = 500)
    val address: String?,
    @field:Size(max = 10)
    val birthDate: String?,
    @field:Size(max = 20)
    val language: String?,
    @field:Size(max = 2048)
    val photoUrl: String?,
    @field:Size(max = 1000)
    val chronicDisease: String?,
    @field:Size(max = 20)
    val gender: String? = null,
    @field:Size(max = 10)
    val bloodGroup: String? = null,
    @field:Size(max = 1000)
    val allergies: String? = null,
)

data class UpdatePatientRequest(
    @field:Size(max = 255)
    val name: String?,
    @field:Size(max = 50)
    val phone: String?,
    val phones: List<@Size(max = 50) String>? = null,
    @field:Email @field:Size(max = 255)
    val email: String?,
    @field:Size(max = 500)
    val address: String?,
    @field:Size(max = 10)
    val birthDate: String?,
    @field:Size(max = 20)
    val language: String?,
    @field:Size(max = 2048)
    val photoUrl: String?,
    @field:Size(max = 1000)
    val chronicDisease: String?,
    val smsReminderEnabled: Boolean? = null,
    val smsReminderHoursBefore: Int? = null,
    @field:Size(max = 20)
    val gender: String? = null,
    @field:Size(max = 10)
    val bloodGroup: String? = null,
    @field:Size(max = 1000)
    val allergies: String? = null,
)

data class PatientAppointmentRow(
    val id: Long,
    val startAt: String,
    val endAt: String,
    val status: String,
    val location: String?,
    val isVideo: Boolean,
    val reason: String?,
)
