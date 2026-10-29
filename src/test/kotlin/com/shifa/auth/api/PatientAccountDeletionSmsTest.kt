package com.shifa.auth.api


import com.shifa.auth.application.EmailOtpService
import com.shifa.auth.application.PatientAccountDeletionService
import com.shifa.auth.application.SmsOtpService
import com.shifa.auth.domain.AccountDeleteChallenge
import com.shifa.auth.domain.Role
import com.shifa.auth.domain.SmsVerificationCode
import com.shifa.auth.domain.User
import com.shifa.patients.domain.PatientProfile
import com.shifa.shared.security.PatientPrincipal
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.web.server.ResponseStatusException

class PatientAccountDeletionSmsTest {
    private val users = mock(com.shifa.auth.application.Users::class.java)
    private val patients = mock(com.shifa.patients.application.PatientProfiles::class.java)
    private val challenges = mock(com.shifa.auth.application.AccountDeleteChallenges::class.java)
    private val emailOtp = mock(EmailOtpService::class.java)
    private val smsOtp = mock(SmsOtpService::class.java)
    private val deletion = mock(PatientAccountDeletionService::class.java)
    private val attempts = mock(com.shifa.auth.application.AccountDeleteChallengeAttempts::class.java)
    private val controller = PatientAccountDeletionController(users, patients, challenges, emailOtp, deletion, smsOtp, attempts)

    private val user = User(id = 7L, email = null, phone = "+998901234567", passwordHash = "x", role = Role.PATIENT)
    private val principal = PatientPrincipal(user, listOf(org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_PATIENT")))
    private val challengeId = UUID.randomUUID()

    private fun challenge() = AccountDeleteChallenge(user = user, challengeId = challengeId, expiresAt = OffsetDateTime.now().plusMinutes(5))

    init {
        `when`(users.findById(7L)).thenReturn(Optional.of(user))
        `when`(patients.findByUserId(7L)).thenReturn(Optional.of(PatientProfile(id = 1L, fullName = "P", user = user)))
        `when`(challenges.save(org.mockito.ArgumentMatchers.any(AccountDeleteChallenge::class.java) ?: challenge())).thenAnswer { it.arguments[0] }
        `when`(challenges.findByChallengeId(challengeId)).thenReturn(Optional.of(challenge()))
    }

    @Test
    fun `request offers SMS for a phone-only patient and does not change the account yet`() {
        val resp = controller.requestDeletion(principal)
        assertEquals(listOf("SMS"), resp.channels)
        assertEquals("***67", resp.maskedPhone)
        assertEquals(User.AccountStatus.ACTIVE, user.accountStatus)
    }

    @Test
    fun `confirm with a valid SMS code deletes, with a wrong one it refuses and counts the attempt`() {
        `when`(smsOtp.verify("+998901234567", "123456", SmsVerificationCode.PURPOSE_ACCOUNT_DELETION)).thenReturn(true)
        controller.confirmDeletion(principal, com.shifa.auth.api.dto.ConfirmDeleteAccountRequest(challengeId.toString(), smsOtp = "123456"))
        verify(deletion).deletePatientAccount(7L)

        `when`(smsOtp.verify(anyString(), anyString(), anyString())).thenReturn(false)
        `when`(challenges.findByChallengeId(challengeId)).thenReturn(Optional.of(challenge()))
        val e = assertThrows(ResponseStatusException::class.java) {
            controller.confirmDeletion(principal, com.shifa.auth.api.dto.ConfirmDeleteAccountRequest(challengeId.toString(), smsOtp = "000000"))
        }
        assertEquals(400, e.statusCode.value())
        verify(attempts).recordFailure(challengeId) // counted outside the rolled-back transaction
        verify(deletion).deletePatientAccount(7L) // still exactly once
        verify(emailOtp, never()).verify(anyString(), anyString(), anyString())
    }
}
