package com.shifa.auth.api


import com.shifa.auth.application.EmailOtpService
import com.shifa.auth.application.SmsOtpService
import com.shifa.auth.application.UserActivityService
import com.shifa.auth.application.UserManagementService
import com.shifa.auth.application.UserRoles
import com.shifa.auth.application.UserSessions
import com.shifa.auth.application.Users
import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import com.shifa.auth.domain.UserRole
import com.shifa.auth.domain.UserSession
import com.shifa.patients.domain.PatientProfile
import com.shifa.auth.infrastructure.FirebaseAuthService
import com.shifa.clinics.infrastructure.ClinicMembershipRepository
import com.shifa.clinics.infrastructure.ClinicRepository
import com.shifa.clinics.infrastructure.InvitationKeyRepository
import com.shifa.doctors.application.DoctorProfiles
import com.shifa.patients.application.PatientProfiles
import com.shifa.shared.security.JwtService
import com.shifa.shared.security.PrincipalResolverService
import java.time.Instant
import java.util.Optional
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * /api/auth/create-patient-for-doctor is public. It must (1) verify an OTP on every path and
 * (2) select the account only by the identifier that was verified. Before the fix, a doctor's
 * phone number alone yielded a PATIENT token, and a victim's phone paired with the attacker's
 * own email + code did the same.
 */
@WebMvcTest(
    controllers = [AuthController::class],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [
                com.shifa.shared.security.AiRateLimitFilter::class,
                com.shifa.shared.security.JwtAuthFilter::class,
                com.shifa.shared.security.UserRateLimitFilter::class,
            ],
        )
    ]
)
@AutoConfigureMockMvc(addFilters = false)
class AuthCreatePatientForDoctorOtpTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockBean lateinit var users: Users
    @MockBean lateinit var invites: InvitationKeyRepository
    @MockBean lateinit var doctors: DoctorProfiles
    @MockBean lateinit var patients: PatientProfiles
    @MockBean lateinit var userSessions: UserSessions
    @MockBean lateinit var encoder: PasswordEncoder
    @MockBean lateinit var jwt: JwtService
    @MockBean lateinit var userActivityService: UserActivityService
    @MockBean lateinit var userManagementService: UserManagementService
    @MockBean lateinit var userRoles: UserRoles
    @MockBean lateinit var firebaseAuthService: FirebaseAuthService
    @MockBean lateinit var emailOtpService: EmailOtpService
    @MockBean lateinit var smsOtpService: SmsOtpService
    @MockBean lateinit var clinicMemberships: ClinicMembershipRepository
    @MockBean lateinit var clinics: ClinicRepository
    @MockBean lateinit var principalResolverService: PrincipalResolverService

    private val doctorPhone = "+998901234567"
    private val doctorEmail = "doctor@example.com"

    private fun doctor(id: Long = 7L, phone: String? = doctorPhone, email: String? = doctorEmail) =
        User(id = id, email = email, phone = phone, passwordHash = "hash", role = Role.DOCTOR)

    /** A doctor who already holds a PATIENT role: the endpoint just issues a token. */
    private fun stubTokenPathFor(user: User) {
        `when`(userRoles.existsByUserIdAndRole(user.id, Role.DOCTOR)).thenReturn(true)
        `when`(userRoles.existsByUserIdAndRole(user.id, Role.PATIENT)).thenReturn(true)
        `when`(jwt.generate(user.id, user.email ?: user.phone!!, Role.PATIENT.name))
            .thenReturn(JwtService.TokenResult("patient-token", "jti-1", Instant.now().plusSeconds(60)))
    }

    private fun call(body: String) = mockMvc.perform(
        post("/api/auth/create-patient-for-doctor")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
    )

    // ---- phone-only path (the original hole) ----

    @Test
    fun `phone only without sms code is rejected before any account lookup`() {
        call("""{"phone":"$doctorPhone"}""")
            .andExpect(status().isBadRequest)
            .andExpect(content().string(containsString("SMS verification code required")))

        verifyNoInteractions(users, userSessions, jwt)
    }

    @Test
    fun `phone only with wrong sms code is rejected before any account lookup`() {
        `when`(smsOtpService.verify(anyString(), anyString())).thenReturn(false)

        call("""{"phone":"$doctorPhone","smsOtp":"000000"}""")
            .andExpect(status().isBadRequest)
            .andExpect(content().string(containsString("Invalid or expired SMS verification code")))

        verify(smsOtpService).verify(doctorPhone, "000000")
        verifyNoInteractions(users, userSessions, jwt)
    }

    @Test
    fun `phone only with valid sms code issues the patient token for that phone`() {
        val user = doctor()
        `when`(smsOtpService.verify(doctorPhone, "123456")).thenReturn(true)
        `when`(users.findByPhone(doctorPhone)).thenReturn(Optional.of(user))
        stubTokenPathFor(user)

        call("""{"phone":"$doctorPhone","smsOtp":"123456"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.token").value("patient-token"))
            .andExpect(jsonPath("$.forcePasswordReset").value(false))

        verify(users, atLeastOnce()).findByPhone(doctorPhone)
        verifyNoInteractions(emailOtpService)
    }

    // ---- email path (what the apps send today) ----

    @Test
    fun `email with phone and valid email code keeps working exactly as before`() {
        val user = doctor()
        `when`(emailOtpService.verify(doctorEmail, "654321")).thenReturn(true)
        `when`(users.findByEmail(doctorEmail)).thenReturn(Optional.of(user))
        stubTokenPathFor(user)

        call("""{"phone":"$doctorPhone","email":"$doctorEmail","emailOtp":"654321"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.token").value("patient-token"))
            .andExpect(jsonPath("$.forcePasswordReset").value(false))

        verifyNoInteractions(smsOtpService)
        verify(users, never()).findByPhone(anyString())
    }

    @Test
    fun `email with phone and valid code works when the account has no stored phone`() {
        val user = doctor(phone = null)
        `when`(emailOtpService.verify(doctorEmail, "654321")).thenReturn(true)
        `when`(users.findByEmail(doctorEmail)).thenReturn(Optional.of(user))
        stubTokenPathFor(user)

        call("""{"phone":"$doctorPhone","email":"$doctorEmail","emailOtp":"654321"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.token").value("patient-token"))
    }

    @Test
    fun `email with wrong code is rejected before any account lookup`() {
        `when`(emailOtpService.verify(anyString(), anyString())).thenReturn(false)

        call("""{"phone":"$doctorPhone","email":"$doctorEmail","emailOtp":"000000"}""")
            .andExpect(status().isBadRequest)
            .andExpect(content().string(containsString("Invalid or expired email verification code")))

        verifyNoInteractions(users, userSessions, jwt, smsOtpService)
    }

    @Test
    fun `email without its code is still rejected`() {
        call("""{"phone":"$doctorPhone","email":"$doctorEmail"}""")
            .andExpect(status().isBadRequest)
            .andExpect(content().string(containsString("Email OTP required")))

        verifyNoInteractions(users, smsOtpService, jwt)
    }

    @Test
    fun `email sent in a different case than stored still finds the account`() {
        val user = doctor() // stored lowercase
        `when`(emailOtpService.verify("Doctor@Example.com", "654321")).thenReturn(true)
        `when`(users.findByEmail("Doctor@Example.com")).thenReturn(Optional.empty())
        `when`(users.findByEmailIgnoreCase("Doctor@Example.com")).thenReturn(Optional.of(user))
        stubTokenPathFor(user)

        call("""{"phone":"$doctorPhone","email":"Doctor@Example.com","emailOtp":"654321"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.token").value("patient-token"))
    }

    @Test
    fun `case-variant duplicate emails give not found instead of a server error`() {
        `when`(emailOtpService.verify(doctorEmail, "654321")).thenReturn(true)
        `when`(users.findByEmail(doctorEmail)).thenReturn(Optional.empty())
        `when`(users.findByEmailIgnoreCase(doctorEmail))
            .thenThrow(org.springframework.dao.IncorrectResultSizeDataAccessException(1, 2))

        call("""{"email":"$doctorEmail","emailOtp":"654321"}""")
            .andExpect(status().isNotFound)
            .andExpect(content().string(containsString("No account found")))

        verifyNoInteractions(userSessions, jwt)
    }

    @Test
    fun `first conversion creates the patient role and profile and issues a token`() {
        val user = doctor()
        `when`(emailOtpService.verify(doctorEmail, "654321")).thenReturn(true)
        `when`(users.findByEmail(doctorEmail)).thenReturn(Optional.of(user))
        `when`(userRoles.existsByUserIdAndRole(user.id, Role.DOCTOR)).thenReturn(true)
        `when`(userRoles.existsByUserIdAndRole(user.id, Role.PATIENT)).thenReturn(false)
        `when`(doctors.findByUserId(user.id)).thenReturn(Optional.empty())
        `when`(jwt.generate(user.id, doctorEmail, Role.PATIENT.name))
            .thenReturn(JwtService.TokenResult("patient-token", "jti-2", Instant.now().plusSeconds(60)))

        call("""{"phone":"$doctorPhone","email":"$doctorEmail","emailOtp":"654321"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.token").value("patient-token"))

        verify(userRoles).save(
            org.mockito.ArgumentMatchers.argThat<UserRole> { it.user.id == user.id && it.role == Role.PATIENT }
                ?: UserRole(user = user, role = Role.PATIENT),
        )
        verify(patients).save(
            org.mockito.ArgumentMatchers.argThat<PatientProfile> { it.user?.id == user.id && it.phone == doctorPhone }
                ?: PatientProfile(fullName = "P", user = user),
        )
        verify(userSessions).save(
            org.mockito.ArgumentMatchers.argThat<UserSession> { it.tokenJti == "jti-2" && it.user.id == user.id }
                ?: UserSession(user = user, expiresAt = Instant.now().atOffset(java.time.ZoneOffset.UTC)),
        )
    }

    @Test
    fun `disabled account is refused after verification`() {
        val user = doctor().apply { enabled = false }
        `when`(emailOtpService.verify(doctorEmail, "654321")).thenReturn(true)
        `when`(users.findByEmail(doctorEmail)).thenReturn(Optional.of(user))

        call("""{"email":"$doctorEmail","emailOtp":"654321"}""")
            .andExpect(status().isForbidden)
            .andExpect(content().string(containsString("Account is disabled")))

        verifyNoInteractions(userSessions, jwt)
    }

    // ---- the pairing bypass: victim's phone + attacker's verified email ----

    @Test
    fun `verified attacker email cannot select a victim account by phone`() {
        val attacker = doctor(id = 99L, phone = "+998909999999", email = "attacker@example.com")
        `when`(emailOtpService.verify("attacker@example.com", "111111")).thenReturn(true)
        `when`(users.findByEmail("attacker@example.com")).thenReturn(Optional.of(attacker))

        call("""{"phone":"$doctorPhone","email":"attacker@example.com","emailOtp":"111111"}""")
            .andExpect(status().isNotFound)
            .andExpect(content().string(containsString("No account found")))

        verify(users, never()).findByPhone(anyString())
        verifyNoInteractions(userSessions, jwt, userRoles)
    }
}
