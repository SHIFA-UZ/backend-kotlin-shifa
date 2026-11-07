package com.shifa.auth.api


import com.shifa.auth.application.EmailOtpService
import com.shifa.auth.application.SmsOtpService
import com.shifa.auth.application.UserActivityService
import com.shifa.auth.application.UserManagementService
import com.shifa.auth.application.UserRoles
import com.shifa.auth.application.UserSessions
import com.shifa.auth.application.Users
import com.shifa.auth.application.SmsOtpSendFailure
import com.shifa.auth.application.SmsOtpSendResult
import com.shifa.auth.domain.EmailVerificationCode
import com.shifa.auth.domain.SmsVerificationCode
import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import com.shifa.auth.domain.UserRole
import com.shifa.auth.domain.UserSession
import com.shifa.auth.infrastructure.FirebaseAuthService
import com.shifa.clinics.application.ClinicMemberships
import com.shifa.clinics.application.Clinics
import com.shifa.clinics.application.InvitationKeys
import com.shifa.doctors.application.DoctorProfiles
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.patients.application.PatientProfiles
import com.shifa.patients.domain.PatientProfile
import com.shifa.shared.security.JwtService
import com.shifa.shared.security.PrincipalResolverService
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Optional
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

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
@Import(com.shifa.shared.config.CacheConfig::class) // Boot 4's @WebMvcTest slice no longer brings a CacheManager for @EnableCaching
/** Doctor SMS code sign-in: `send-login-sms-otp` and `verify-login-sms-otp`. */
class AuthSmsSignInTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var users: Users

    @MockitoBean
    lateinit var invites: InvitationKeys

    @MockitoBean
    lateinit var doctors: DoctorProfiles

    @MockitoBean
    lateinit var patients: PatientProfiles

    @MockitoBean
    lateinit var userSessions: UserSessions

    @MockitoBean
    lateinit var encoder: PasswordEncoder

    @MockitoBean
    lateinit var jwt: JwtService

    @MockitoBean
    lateinit var userActivityService: UserActivityService

    @MockitoBean
    lateinit var userManagementService: UserManagementService

    @MockitoBean
    lateinit var userRoles: UserRoles

    @MockitoBean
    lateinit var firebaseAuthService: FirebaseAuthService

    @MockitoBean
    lateinit var emailOtpService: EmailOtpService

    @MockitoBean
    lateinit var smsOtpService: SmsOtpService

    @MockitoBean
    lateinit var clinicMemberships: ClinicMemberships

    @MockitoBean
    lateinit var clinics: Clinics

    @MockitoBean
    lateinit var principalResolverService: PrincipalResolverService

    private val phone = "+998901234567"
    private val user = User(id = 7L, email = "doc@example.com", phone = phone, passwordHash = "hash", role = Role.DOCTOR)

    private fun doctorSignedUp() {
        `when`(users.findByPhone(phone)).thenReturn(Optional.of(user))
        `when`(userRoles.existsByUserIdAndRole(user.id, Role.DOCTOR)).thenReturn(true)
    }

    private fun send(body: String) =
        mockMvc.perform(post("/api/auth/send-login-sms-otp").contentType(MediaType.APPLICATION_JSON).content(body))

    private fun verify(code: String) =
        mockMvc.perform(
            post("/api/auth/verify-login-sms-otp").contentType(MediaType.APPLICATION_JSON)
                .content("""{"phone":"+998 90 123 45 67","code":"$code"}"""),
        )

    @Test
    fun `a doctor's number gets the sign-in code by sms`() {
        doctorSignedUp()
        `when`(smsOtpService.sendCode(phone, SmsVerificationCode.PURPOSE_LOGIN)).thenReturn(SmsOtpSendResult.Success)

        send("""{"phone":"+998 90 123 45 67"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.channel").value("sms"))
        verifyNoInteractions(emailOtpService)
    }

    @Test
    fun `when the sms can't go out the code goes to the account's e-mail, masked`() {
        doctorSignedUp()
        `when`(smsOtpService.sendCode(phone, SmsVerificationCode.PURPOSE_LOGIN))
            .thenReturn(SmsOtpSendResult.Failure(SmsOtpSendFailure.SMS_PROVIDER_FAILED))
        `when`(emailOtpService.sendCode("doc@example.com", EmailVerificationCode.PURPOSE_LOGIN)).thenReturn(true)

        send("""{"phone":"$phone"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.channel").value("email"))
            .andExpect(jsonPath("$.smsFallbackUsed").value(true))
            .andExpect(jsonPath("$.emailHint").value("d***@example.com"))
    }

    @Test
    fun `an unknown number is not found and nothing is sent`() {
        `when`(users.findByPhone(anyString())).thenReturn(Optional.empty())

        send("""{"phone":"$phone"}""").andExpect(status().isNotFound)
        verifyNoInteractions(smsOtpService)
    }

    @Test
    fun `a number that is not uzbek is refused`() {
        send("""{"phone":"+4915112345678"}""").andExpect(status().isBadRequest)
        verifyNoInteractions(smsOtpService)
    }

    @Test
    fun `a patient-only account can't sign in to the doctor app`() {
        `when`(users.findByPhone(phone)).thenReturn(Optional.of(user))
        `when`(userRoles.existsByUserIdAndRole(user.id, Role.DOCTOR)).thenReturn(false)

        send("""{"phone":"$phone"}""").andExpect(status().isForbidden)
        verifyNoInteractions(smsOtpService)
    }

    @Test
    fun `the right sms code signs the doctor in with a doctor token`() {
        doctorSignedUp()
        `when`(smsOtpService.verify(phone, "123456", SmsVerificationCode.PURPOSE_LOGIN)).thenReturn(true)
        `when`(jwt.generate(user.id, "doc@example.com", Role.DOCTOR.name))
            .thenReturn(JwtService.TokenResult("jwt-token", "jti-1", Instant.now().plusSeconds(3600)))

        verify("123456").andExpect(status().isOk).andExpect(jsonPath("$.token").value("jwt-token"))
    }

    @Test
    fun `the e-mailed fallback code is accepted too`() {
        doctorSignedUp()
        `when`(smsOtpService.verify(phone, "654321", SmsVerificationCode.PURPOSE_LOGIN)).thenReturn(false)
        `when`(emailOtpService.verify("doc@example.com", "654321", EmailVerificationCode.PURPOSE_LOGIN)).thenReturn(true)
        `when`(jwt.generate(user.id, "doc@example.com", Role.DOCTOR.name))
            .thenReturn(JwtService.TokenResult("jwt-token", "jti-1", Instant.now().plusSeconds(3600)))

        verify("654321").andExpect(status().isOk)
    }

    @Test
    fun `a wrong code is unauthorized and no session is made`() {
        doctorSignedUp()

        verify("000000").andExpect(status().isUnauthorized)
        verifyNoInteractions(jwt, userSessions)
    }
}
