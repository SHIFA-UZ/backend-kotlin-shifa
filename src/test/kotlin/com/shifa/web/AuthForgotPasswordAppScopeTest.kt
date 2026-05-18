package com.shifa.web

import com.shifa.domain.DoctorProfile
import com.shifa.domain.PatientProfile
import com.shifa.domain.Role
import com.shifa.domain.User
import com.shifa.domain.UserRole
import com.shifa.config.StaticResourceConfig
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
import com.shifa.service.UserActivityService
import com.shifa.service.UserManagementService
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
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
import java.util.Optional

@WebMvcTest(
    controllers = [AuthController::class],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [StaticResourceConfig::class])
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
    lateinit var clinicMemberships: ClinicMembershipRepository

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
        `when`(users.findByEmail("doc-only@example.com")).thenReturn(Optional.of(user))
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
        `when`(users.findByEmail("patient-only@example.com")).thenReturn(Optional.of(user))
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
        `when`(users.findByEmail("not-admin@example.com")).thenReturn(Optional.of(user))
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
        `when`(users.findByEmail("dual-role@example.com")).thenReturn(Optional.of(user))
        `when`(userRoles.findByUserId(user.id)).thenReturn(
            listOf(
                UserRole(user = user, role = Role.DOCTOR),
                UserRole(user = user, role = Role.PATIENT)
            )
        )
        `when`(doctors.findByUserId(user.id)).thenReturn(Optional.of(doctorProfile))
        `when`(patients.findByUserId(user.id)).thenReturn(Optional.of(patientProfile))
        `when`(emailOtpService.sendCode("dual-role@example.com", com.shifa.domain.EmailVerificationCode.PURPOSE_FORGOT_PASSWORD))
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
}
