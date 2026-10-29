package com.shifa.auth.api.dto

import com.shifa.clinics.domain.InvitationKey
import com.shifa.doctors.domain.DoctorProfile
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class VerifyKeyRequest(@field:NotBlank val key: String)

data class VerifyKeyResponse(
    val valid: Boolean,
    val purpose: String? = null,
    val clinicId: Long? = null,
    val clinicName: String? = null,
    val emailSentTo: String? = null,
)

data class CheckExistingPatientRequest(
    @field:NotBlank val firstName: String,
    @field:NotBlank val lastName: String,
    /** Optional; when set, used together with email to detect an existing patient account. */
    val phone: String? = null,
    @field:NotBlank @field:Email val email: String
)

data class CheckExistingPatientResponse(
    val found: Boolean,
    val fullName: String? = null,
    val photoUrl: String? = null,
    val email: String? = null
)

data class CheckExistingDoctorRequest(
    val phone: String? = null,
    val email: String? = null
)

data class CheckExistingDoctorResponse(
    val firstName: String,
    val lastName: String
)

data class CheckIdentifierRequest(
    val phone: String? = null,
    val email: String? = null
)

data class CheckIdentifierResponse(
    val type: String, // "doctor" | "patient" | "none"
    val firstName: String? = null,
    val lastName: String? = null
)

data class SendEmailOtpRequest(
    @field:NotBlank @field:Email val email: String,
    val purpose: String? = null
)

data class SendEmailOtpResponse(val sent: Boolean = true)

/** Response when OTP channel may be SMS or email (e.g. SMS failure fallback). */
data class SendOtpChannelResponse(
    val sent: Boolean = true,
    val channel: String,
    val smsFallbackUsed: Boolean = false,
)

data class SendSmsOtpRequest(
    @field:NotBlank val phone: String,
    val purpose: String? = null,
    /** When SMS delivery fails, send OTP to this email instead (registration flow). */
    val fallbackEmail: String? = null,
)

data class SendLoginOtpRequest(@field:NotBlank @field:Email val email: String)

data class VerifyEmailOtpRequest(
    @field:NotBlank @field:Email val email: String,
    @field:NotBlank val code: String
)

data class SendForgotPasswordOtpRequest(
    @field:NotBlank val identifier: String,
    val app: String? = null
)

/**
 * Send a forgot-password OTP. Accepts email or phone as identifier;
 * looks up the user and sends OTP to their email address.
 */
data class SendForgotPasswordOtpResponse(val sent: Boolean = true, val channel: String)

data class CreatePatientForDoctorRequest(
    val phone: String? = null,
    val email: String? = null,
    val emailOtp: String? = null,
    /** Required when no email is given: SMS code sent via /send-sms-otp (REGISTRATION purpose). */
    val smsOtp: String? = null,
)

data class RegisterRequest(
    @field:NotBlank val firstName: String,
    @field:NotBlank val lastName: String,
    /** Optional. When omitted or blank, the account is created/linked using email only (must still be unique when
     * set). */
    val phone: String? = null,
    @field:NotBlank @field:Email val email: String,
    @field:NotBlank val password: String,
    @field:NotBlank val key: String,
    /** Optional IANA timezone (e.g. Europe/Berlin). If provided, used for new doctor profile so appointment times
     * are correct. */
    val timeZone: String? = null
)

/** Receptionist/clinic staff self-signup via [InvitationKey.PURPOSE_CLINIC_RECEPTIONIST_INVITE]; no
 * [DoctorProfile]. */
data class RegisterClinicStaffRequest(
    @field:NotBlank val firstName: String,
    @field:NotBlank val lastName: String,
    val phone: String? = null,
    @field:NotBlank @field:Email val email: String,
    @field:NotBlank val password: String,
    @field:NotBlank val key: String,
    val timeZone: String? = null,
)

data class LoginRequest(@field:NotBlank val username: String, @field:NotBlank val password: String)

data class AdminVerifyLoginOtpRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val password: String,
    @field:NotBlank val code: String
)

data class AdminLoginOtpSentResponse(val sent: Boolean = true, val emailHint: String? = null)

data class RegisterPatientRequest(
    @field:NotBlank val firstName: String,
    @field:NotBlank val lastName: String,
    val phone: String? = null,
    val email: String? = null,
    @field:NotBlank val password: String,
    val birthDate: String? = null,
    val gender: String? = null,
    val address: String? = null,
    val language: String? = null,
    val emailOtp: String? = null,
    val smsOtp: String? = null,
)

data class ChangePasswordRequest(
    val currentPassword: String? = null,
    @field:NotBlank val newPassword: String
)

data class ForgotPasswordResetRequest(
    val idToken: String? = null,
    val email: String? = null,
    val emailOtp: String? = null,
    val phone: String? = null,
    val smsOtp: String? = null,
    val app: String? = null,
    @field:NotBlank val newPassword: String
)
