package com.shifa.web.dto

/**
 * Parsed JSON from the booking-intent completion (OpenAI).
 */
data class PatientBookingIntentResolution(
    val bookNow: Boolean = false,
    val doctorId: Long? = null,
    val preferredStartAtUtc: String? = null,
    val isVideo: Boolean? = null,
    val userExplicitConsentToAutoBook: Boolean = false
)
