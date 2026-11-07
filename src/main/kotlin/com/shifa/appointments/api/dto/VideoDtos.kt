package com.shifa.appointments.api.dto

data class VideoTokenRequest(
    val appointmentId: Long,
    val roomName: String? = null
)

data class VideoTokenResponse(
    val token: String,
    val roomUrl: String,
    val roomName: String
)

/** `GET /api/video/appointments/{id}/presence`: whether the visit's patient is in its video room right now. */
data class VideoPresenceResponse(
    val patientInRoom: Boolean,
    val patientJoinedAt: java.time.Instant? = null,
)
