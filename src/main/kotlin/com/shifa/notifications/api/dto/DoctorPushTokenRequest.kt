package com.shifa.notifications.api.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/** `POST /api/doctors/me/fcm-tokens`: this device's FCM token. */
data class DoctorPushTokenRequest(
    @field:NotBlank @field:Size(max = 512) val token: String,
    /** `android`, `ios` or `web`. */
    @field:Pattern(regexp = "android|ios|web") val platform: String? = null,
)
