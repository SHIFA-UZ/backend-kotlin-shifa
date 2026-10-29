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
