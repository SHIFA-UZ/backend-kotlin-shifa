package com.shifa.admin.api.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class GenerateTokenRequest(
    val expiresInDays: Int? = null,
    val purpose: String = "DOCTOR_ONBOARDING",
    val notes: String? = null,
    val sendEmail: Boolean = false,
    @field:Email val emailTo: String? = null
)

data class UserResponse(
    val id: Long,
    val email: String?,
    val phone: String?,
    val role: String,
    val enabled: Boolean,
    val lastLoginAt: String?,
    val failedLoginAttempts: Int,
    val lockedUntil: String?,
    val subscriptionTier: String,
    val profile: Map<String, Any>?,
    val deviceRegistered: Boolean = false,
    val hasProfile: Boolean = false,
    val createdAt: String? = null,
    val emailVerified: Boolean = false,
)

data class UpdateSubscriptionTierRequest(
    @field:NotBlank val tier: String
)

data class AdminPatientProfileResponse(
    val patientProfileId: Long,
    val fullName: String,
    val phone: String?,
    val email: String?,
    val createdAt: String?,
    val createdByDoctorId: Long?,
    val createdByDoctorName: String?,
)

data class CreateAdminRequest(
    @NotBlank val email: String,
    @NotBlank val password: String,
    @NotBlank val firstName: String,
    @NotBlank val lastName: String,
    val adminLevel: String? = "ADMIN"
)

data class AdminClinicUpsertRequest(
    @field:NotBlank val name: String,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    /** IANA timezone id, e.g. Asia/Tashkent */
    val timeZone: String? = null,
    /** ISO 4217 code, e.g. UZS */
    val currency: String? = null,
)

data class AdminClinicMemberRoleRequest(
    @field:NotBlank val membershipRole: String,
)
