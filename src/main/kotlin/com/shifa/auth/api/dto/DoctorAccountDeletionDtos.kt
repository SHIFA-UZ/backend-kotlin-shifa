package com.shifa.auth.api.dto

import jakarta.validation.constraints.NotBlank

/** `POST /api/doctors/me/delete-account/request`: where the code went and how long it lives. */
data class DoctorDeleteAccountRequestResponse(val maskedEmail: String, val expiresInSeconds: Long)

/** `POST /api/doctors/me/delete-account/confirm`. */
data class ConfirmDoctorDeleteAccountRequest(@field:NotBlank val code: String)
