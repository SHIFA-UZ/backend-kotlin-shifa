package com.shifa.auth.api.dto

import jakarta.validation.constraints.NotBlank

data class DeleteAccountRequestResponse(
    val challengeId: String,
    val maskedPhone: String? = null,
    val expiresInSeconds: Int = 300,
    /** Which verification channels this account can use: "EMAIL" and/or "SMS" (patient-app ask 8). */
    val channels: List<String> = emptyList(),
    val maskedEmail: String? = null,
)

data class ConfirmDeleteAccountRequest(
    @field:NotBlank val challengeId: String,
    val email: String? = null,
    val emailOtp: String? = null,
    /** SMS code sent to the account's phone with purpose ACCOUNT_DELETION (phone-only patients, ask 8). */
    val smsOtp: String? = null,
)
