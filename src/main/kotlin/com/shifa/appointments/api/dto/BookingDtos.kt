package com.shifa.appointments.api.dto

/** startAt: ISO 8601 UTC (e.g. 2026-02-12T13:00:00Z). No timezone guessing. */
data class BookReq(
    val startAt: String,
    val slotMinutes: Int,
    val patientId: Long?,
    val location: String?,
    val reason: String?,
    val isVideo: Boolean,
    /** Optional structured location FK. Required when doctor has multiple locations and !isVideo. */
    val locationId: Long? = null,
    /** Calendar doctor receiving the booking (defaults to authenticated doctor). */
    val resourceDoctorId: Long? = null
)

/** startAt/endAt: ISO 8601 UTC. */
data class ApptDto(
    val id: Long?,
    val startAt: String,
    val endAt: String,
    val patientId: Long?,
    val patientName: String,
    val location: String,
    val reason: String?,
    val status: String,
    val locationId: Long? = null,
    val locationLabel: String? = null
)
