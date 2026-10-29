package com.shifa.web

import com.shifa.domain.AccountDeleteChallenge
import com.shifa.domain.User
import com.shifa.repo.AccountDeleteChallengeRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.repo.UserRepository
import com.shifa.security.PatientPrincipal
import com.shifa.service.EmailOtpService
import com.shifa.service.PatientAccountDeletionService
import com.shifa.service.SmsOtpService
import com.shifa.util.PhoneNormalizer
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

@RestController
@RequestMapping("/api/patients/me/delete-account")
class PatientAccountDeletionController(
    private val users: UserRepository,
    private val patientProfiles: PatientProfileRepository,
    private val challenges: AccountDeleteChallengeRepository,
    private val emailOtpService: EmailOtpService,
    private val deletionService: PatientAccountDeletionService,
    private val smsOtpService: SmsOtpService,
) {
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

    @PostMapping("/request")
    @Transactional
    fun requestDeletion(
        @AuthenticationPrincipal principal: PatientPrincipal,
    ): DeleteAccountRequestResponse {
        val userId = principal.user.id
        val user = users.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }
        if (!user.enabled || user.accountStatus == User.AccountStatus.DELETED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Account already deleted")
        }

        // Ensure patient profile exists for this PATIENT principal.
        patientProfiles.findByUserId(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Patient profile not found for user $userId")
        }

        val now = OffsetDateTime.now()
        val expiresAt = now.plusMinutes(5)
        val c = challenges.save(
            AccountDeleteChallenge(
                user = user,
                expiresAt = expiresAt,
            )
        )

        // The account status changes only once the code is confirmed (ask 8); requesting is reversible.
        val channels = listOfNotNull(
            user.email?.takeIf { it.isNotBlank() }?.let { "EMAIL" },
            user.phone?.let { PhoneNormalizer.normalize(it) }?.takeIf { PhoneNormalizer.isUzbekMobile(it) }?.let { "SMS" },
        )
        return DeleteAccountRequestResponse(
            challengeId = c.challengeId.toString(),
            maskedPhone = user.phone?.let { maskPhone(it) },
            expiresInSeconds = 300,
            channels = channels,
            maskedEmail = user.email?.let { maskEmail(it) },
        )
    }

    @PostMapping("/confirm")
    @Transactional
    fun confirmDeletion(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @RequestBody @Valid body: ConfirmDeleteAccountRequest,
    ): Map<String, Any> {
        val userId = principal.user.id
        val user = users.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }
        if (!user.enabled || user.accountStatus == User.AccountStatus.DELETED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Account already deleted")
        }

        val challengeUuid = runCatching { UUID.fromString(body.challengeId.trim()) }.getOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid challenge")
        val challenge = challenges.findByChallengeId(challengeUuid).orElseThrow {
            ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid challenge")
        }
        if (challenge.user.id != userId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid challenge")
        }
        val now = OffsetDateTime.now()
        if (challenge.used || challenge.expiresAt.isBefore(now)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Challenge expired or already used")
        }
        if (challenge.attemptCount >= 5) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts")
        }

        // Verify identity with the account's own phone (SMS code) or email (email code).
        if (!body.smsOtp.isNullOrBlank()) {
            val phone = user.phone?.let { PhoneNormalizer.normalize(it) }
            if (phone == null || !smsOtpService.verify(phone, body.smsOtp.trim(), com.shifa.domain.SmsVerificationCode.PURPOSE_ACCOUNT_DELETION)) {
                challenge.attemptCount += 1
                challenges.save(challenge)
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired verification code")
            }
        } else if (!body.email.isNullOrBlank() && !body.emailOtp.isNullOrBlank()) {
            val emailNorm = body.email.trim().lowercase()
            if (!emailOtpService.verify(emailNorm, body.emailOtp.trim(),
                    com.shifa.domain.EmailVerificationCode.PURPOSE_ACCOUNT_DELETION)) {
                challenge.attemptCount += 1
                challenges.save(challenge)
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired verification code")
            }
            val userEmail = user.email?.trim()?.lowercase()
            if (userEmail == null || userEmail != emailNorm) {
                challenge.attemptCount += 1
                challenges.save(challenge)
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Email does not match account")
            }
        } else {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide email and emailOtp")
        }

        // Mark challenge used before deletion work.
        challenge.used = true
        challenge.usedAt = now
        challenges.save(challenge)
        user.accountStatus = User.AccountStatus.PENDING_DELETION
        user.deletionRequestedAt = now
        users.save(user)

        deletionService.deletePatientAccount(userId)

        return mapOf("success" to true, "message" to "Account deleted")
    }

    private fun maskEmail(email: String): String {
        val at = email.indexOf('@')
        if (at <= 1) return "***"
        return email.take(1) + "***" + email.substring(at)
    }

    private fun maskPhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        if (digits.length < 4) return "***"
        val last2 = digits.takeLast(2)
        return "***$last2"
    }
}

