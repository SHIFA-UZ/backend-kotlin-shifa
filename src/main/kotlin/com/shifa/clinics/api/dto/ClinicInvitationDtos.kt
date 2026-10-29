package com.shifa.clinics.api.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class CreateInvitationRequest(
    @field:NotBlank @field:Email val email: String,
    /** Currently only RECEPTIONIST. */
    @field:NotBlank val role: String,
    val expiresInDays: Int? = 14,
)

data class CreateInvitationResponse(
    val id: Long,
    val keyCode: String,
    val emailSentTo: String,
    val clinicId: Long,
    val expiresAt: String?,
)

data class InvitationListItemDto(
    val id: Long,
    val emailSentTo: String?,
    val consumed: Boolean,
    val expiresAt: String?,
    /** True when usable for registration (not expired, not revoked/consumed). */
    val pending: Boolean,
)
