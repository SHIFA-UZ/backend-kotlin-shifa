package com.shifa.appointments.api.dto


data class EntryDto(
    val type: String, // FREE_SLOT | APPOINTMENT | BLOCKED
    val startAt: String, // ISO 8601 UTC
    val endAt: String,
    val appointmentId: Long? = null,
    val blockId: Long? = null,
    val blockReason: String? = null,
    val patientId: Long? = null,
    val patientName: String? = null,
    val patientPhotoUrl: String? = null,
    val location: String? = null,
    val reason: String? = null,
    val status: String? = null, // Appointment status: CONFIRMED, COMPLETED, CANCELLED, REQUESTED
    val paymentStatus: String? = null, // For video payment visibility in doctor calendar
    val signatureRequested: Boolean = false,
    val patientSignedAt: String? = null, // ISO when patient signed
    /** Which practice location this slot/appointment belongs to (null for video/legacy). */
    val locationId: Long? = null,
    val locationLabel: String? = null,
    val briefingStatus: String? = null,
    val attachmentCount: Int = 0,
)
