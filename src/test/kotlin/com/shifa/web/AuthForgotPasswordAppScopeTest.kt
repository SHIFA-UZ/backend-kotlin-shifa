package com.shifa.web

import com.shifa.domain.DoctorProfile
import com.shifa.domain.EmailVerificationCode
import com.shifa.domain.PatientProfile
import com.shifa.domain.Role
import com.shifa.domain.User
import com.shifa.domain.UserRole
import com.shifa.domain.UserSession
import com.shifa.repo.ClinicRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.InvitationKeyRepository
import com.shifa.repo.ClinicMembershipRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.repo.UserRepository
import com.shifa.repo.UserRoleRepository
import com.shifa.repo.UserSessionRepository
import com.shifa.security.JwtService
import com.shifa.security.PrincipalResolverService
import com.shifa.service.EmailOtpService
import com.shifa.service.FirebaseAuthService
import com.shifa.service.SmsOtpService
import com.shifa.service.UserActivityService
import com.shifa.service.UserManagementService
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Optional

@WebMvcTest(
    controllers = [AuthController::class],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [
                com.shifa.security.AiRateLimitFilter::class,
                com.shifa.security.JwtAuthFilter::class,
                com.shifa.security.UserRateLimitFilter::class,
            ],
        )
    ]
)
@AutoConfigureMockMvc(addFilters = false)
class AuthForgotPasswordAppScopeTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockBean
    lateinit var users: UserRepository

    @MockBean
    lateinit var invites: InvitationKeyRepository

    @MockBean
    lateinit var doctors: DoctorProfileRepository

    @MockBean
    lateinit var patients: PatientProfileRepository

    @MockBean
    lateinit var userSessions: UserSessionRepository

    @MockBean
    lateinit var encoder: PasswordEncoder

    @MockBean
    lateinit var jwt: JwtService

    @MockBean
    lateinit var userActivityService: UserActivityService

    @MockBean
    lateinit var userManagementService: UserManagementService

    @MockBean
    lateinit var userRoles: UserRoleRepository

    @MockBean
    lateinit var firebaseAuthService: FirebaseAuthService

    @MockBean
    lateinit var emailOtpService: EmailOtpService

    @MockBean
    lateinit var smsOtpService: SmsOtpService

    @MockBean
    lateinit var clinicMemberships: ClinicMembershipRepository

    @MockBean
    lateinit var clinics: ClinicRepository

    @MockBean
    lateinit var principalResolverService: PrincipalResolverService

    @Test
    fun `patient app forgot-password otp rejects doctor-only account`() {
        val user = User(
            id = 101L,
            email = "doc-only@example.com",
            phone = "+998901111111",
            passwordHash = "hash",
            role = Role.DOCTOR
        )
        `when`(users.findByEmailIgnoreCase("doc-only@example.com")).thenReturn(Optional.of(user))
        `when`(userRoles.findByUserId(user.id)).thenReturn(listOf(UserRole(user = user, role = Role.DOCTOR)))

        mockMvc.perform(
            post("/api/auth/send-forgot-password-otp")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"doc-only@example.com","app":"patient"}""")
        )
            .andExpect(status().isForbidden)
            .andExpect(content().string(containsString("No patient account found")))

        verifyNoInteractions(emailOtpService)
    }

    @Test
    fun `doctor app forgot-password otp rejects patient-only account`() {
        val user = User(
            id = 102L,
            email = "patient-only@example.com",
            phone = "+998902222222",
            passwordHash = "hash",
            role = Role.PATIENT
        )
        `when`(users.findByEmailIgnoreCase("patient-only@example.com")).thenReturn(Optional.of(user))
        `when`(userRoles.findByUserId(user.id)).thenReturn(listOf(UserRole(user = user, role = Role.PATIENT)))

        mockMvc.perform(
            post("/api/auth/send-forgot-password-otp")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"patient-only@example.com","app":"doctor"}""")
        )
            .andExpect(status().isForbidden)
            .andExpect(content().string(containsString("No doctor app access")))

        verifyNoInteractions(emailOtpService)
    }

    @Test
    fun `admin app forgot-password otp rejects non-admin account`() {
        val user = User(
            id = 103L,
            email = "not-admin@example.com",
            phone = "+998903333333",
            passwordHash = "hash",
            role = Role.DOCTOR
        )
        `when`(users.findByEmailIgnoreCase("not-admin@example.com")).thenReturn(Optional.of(user))
        `when`(userRoles.findByUserId(user.id)).thenReturn(listOf(UserRole(user = user, role = Role.DOCTOR)))

        mockMvc.perform(
            post("/api/auth/send-forgot-password-otp")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"not-admin@example.com","app":"admin"}""")
        )
            .andExpect(status().isForbidden)
            .andExpect(content().string(containsString("No admin account found")))

        verifyNoInteractions(emailOtpService)
    }

    @Test
    fun `account with both doctor and patient profiles can reset from both apps`() {
        val user = User(
            id = 104L,
            email = "dual-role@example.com",
            phone = "+998904444444",
            passwordHash = "hash",
            role = Role.DOCTOR
        )
        val doctorProfile = DoctorProfile(
            user = user,
            firstName = "Dual",
            lastName = "Role"
        )
        val patientProfile = PatientProfile(
            fullName = "Dual Role",
            email = user.email,
            phone = user.phone,
            user = user
        )
        `when`(users.findByEmailIgnoreCase("dual-role@example.com")).thenReturn(Optional.of(user))
        `when`(userRoles.findByUserId(user.id)).thenReturn(
            listOf(
                UserRole(user = user, role = Role.DOCTOR),
                UserRole(user = user, role = Role.PATIENT)
            )
        )
        `when`(doctors.findByUserId(user.id)).thenReturn(Optional.of(doctorProfile))
        `when`(patients.findByUserId(user.id)).thenReturn(Optional.of(patientProfile))
        `when`(emailOtpService.sendCode("dual-role@example.com", EmailVerificationCode.PURPOSE_FORGOT_PASSWORD))
            .thenReturn(true)

        mockMvc.perform(
            post("/api/auth/send-forgot-password-otp")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"dual-role@example.com","app":"doctor"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sent").value(true))

        mockMvc.perform(
            post("/api/auth/send-forgot-password-otp")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"dual-role@example.com","app":"patient"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sent").value(true))
    }

    @Test
    fun `forgot-password reset clears lockout and login accepts mixed-case email`() {
        val user = User(
            id = 105L,
            email = "Patient@Example.com",
            phone = "+998905555555",
            passwordHash = "old-hash",
            role = Role.PATIENT,
            lockedUntil = OffsetDateTime.now().plusMinutes(30),
            failedLoginAttempts = 5,
        )
        val patientProfile = PatientProfile(
            fullName = "Patient Example",
            email = user.email,
            phone = user.phone,
            user = user,
        )
        val tokenResult = JwtService.TokenResult(
            token = "jwt-token",
            jti = "jti-1",
            expiresAt = Instant.now().plusSeconds(3600),
        )

        `when`(users.findByEmailIgnoreCase("Patient@Example.com")).thenReturn(Optional.of(user))
        `when`(users.findByEmailIgnoreCase("PATIENT@EXAMPLE.COM")).thenReturn(Optional.of(user))
        `when`(users.findByUsername(anyString())).thenReturn(Optional.empty())
        `when`(users.findByPhone(anyString())).thenReturn(Optional.empty())
        `when`(users.save(user)).thenReturn(user)
        `when`(userRoles.findByUserId(user.id)).thenReturn(listOf(UserRole(user = user, role = Role.PATIENT)))
        `when`(userRoles.existsByUserIdAndRole(user.id, Role.PATIENT)).thenReturn(true)
        `when`(patients.findByUserId(user.id)).thenReturn(Optional.of(patientProfile))
        `when`(
            emailOtpService.verify(
                "patient@example.com",
                "123456",
                EmailVerificationCode.PURPOSE_FORGOT_PASSWORD,
            )
        ).thenReturn(true)
        `when`(encoder.encode("NewPass1!")).thenReturn("new-hash")
        `when`(encoder.matches("NewPass1!", "new-hash")).thenReturn(true)
        `when`(jwt.generate(user.id, "Patient@Example.com", Role.PATIENT.name)).thenReturn(tokenResult)
        `when`(userSessions.save(any(UserSession::class.java))).thenAnswer { it.arguments[0] }

        mockMvc.perform(
            post("/api/auth/forgot-password-reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"email":"Patient@Example.com","emailOtp":"123456","app":"patient","newPassword":"NewPass1!"}"""
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.token").value("jwt-token"))
            .andExpect(jsonPath("$.forcePasswordReset").value(false))

        assertNull(user.lockedUntil)
        assertEquals(0, user.failedLoginAttempts)
        assertEquals("new-hash", user.passwordHash)

        mockMvc.perform(
            post("/api/auth/login")
                .param("app", "patient")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"PATIENT@EXAMPLE.COM","password":"NewPass1!"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.token").value("jwt-token"))
    }
}
