package com.shifa.auth.api

import com.shifa.auth.application.EmailOtpService
import com.shifa.auth.application.EmailOtpService.OtpCheck
import com.shifa.auth.application.SmsOtpService
import com.shifa.auth.application.UserActivityService
import com.shifa.auth.application.UserManagementService
import com.shifa.auth.application.UserRoles
import com.shifa.auth.application.UserSessions
import com.shifa.auth.application.Users
import com.shifa.auth.domain.EmailVerificationCode
import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import com.shifa.auth.infrastructure.FirebaseAuthService
import com.shifa.clinics.application.ClinicMemberships
import com.shifa.clinics.application.Clinics
import com.shifa.clinics.application.InvitationKeys
import com.shifa.clinics.domain.Clinic
import com.shifa.clinics.domain.ClinicMembership
import com.shifa.clinics.domain.InvitationKey
import com.shifa.doctors.application.DoctorProfiles
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.patients.application.PatientProfiles
import com.shifa.shared.security.JwtService
import com.shifa.shared.security.PrincipalResolverService
import java.time.Instant
import java.util.Optional
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Registration with an invitation key may create accounts, but it may only add a role to an existing account when
 * the caller proves the account is theirs (BE-29). Before the fix, any unused doctor key plus a doctor's e-mail reset
 * that doctor's password. Also covers the optional registration e-mail code (BE-03).
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
        ),
    ],
)
@AutoConfigureMockMvc(addFilters = false)
@Import(com.shifa.shared.config.CacheConfig::class)
class AuthRegisterOwnershipTest {

    @Autowired lateinit var mockMvc: MockMvc

    @MockitoBean lateinit var users: Users
    @MockitoBean lateinit var invites: InvitationKeys
    @MockitoBean lateinit var doctors: DoctorProfiles
    @MockitoBean lateinit var patients: PatientProfiles
    @MockitoBean lateinit var userSessions: UserSessions
    @MockitoBean lateinit var encoder: PasswordEncoder
    @MockitoBean lateinit var jwt: JwtService
    @MockitoBean lateinit var userActivityService: UserActivityService
    @MockitoBean lateinit var userManagementService: UserManagementService
    @MockitoBean lateinit var userRoles: UserRoles
    @MockitoBean lateinit var firebaseAuthService: FirebaseAuthService
    @MockitoBean lateinit var emailOtpService: EmailOtpService
    @MockitoBean lateinit var smsOtpService: SmsOtpService
    @MockitoBean lateinit var clinicMemberships: ClinicMemberships
    @MockitoBean lateinit var clinics: Clinics
    @MockitoBean lateinit var principalResolverService: PrincipalResolverService

    private val email = "doctor@example.uz"
    private val password = "NewPass1!"
    private val registration = EmailVerificationCode.PURPOSE_REGISTRATION

    // Mockito's any() returns null, which Kotlin rejects for non-null parameters.
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyOf(type: Class<T>): T = ArgumentMatchers.any(type) ?: (null as T)

    private fun existing(id: Long = 7L, mail: String? = email) =
        User(id = id, email = mail, phone = null, passwordHash = "old-hash", role = Role.PATIENT)

    @BeforeEach
    fun stubs() {
        `when`(invites.findByKeyCode("KEY-1")).thenReturn(InvitationKey(keyCode = "KEY-1"))
        `when`(users.save(anyOf(User::class.java))).thenAnswer { it.arguments[0] }
        `when`(doctors.save(anyOf(DoctorProfile::class.java))).thenAnswer { it.arguments[0] }
        `when`(encoder.encode(password)).thenReturn("new-hash")
        `when`(jwt.generate(ArgumentMatchers.anyLong(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
            .thenReturn(JwtService.TokenResult("token", "jti", Instant.now().plusSeconds(60)))
    }

    private fun register(extra: String = "") = mockMvc.perform(
        post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(
            """{"firstName":"A","lastName":"B","email":"$email","password":"$password","key":"KEY-1"$extra}""",
        ),
    )

    private fun accountByEmail(user: User) {
        `when`(users.findByEmailIgnoreCase(email)).thenReturn(Optional.of(user))
    }

    @Test
    fun `an existing doctor is refused and keeps the old password`() {
        val user = existing()
        accountByEmail(user)
        `when`(userRoles.existsByUserIdAndRole(user.id, Role.DOCTOR)).thenReturn(true)

        register()
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DOCTOR_EXISTS"))

        assert(user.passwordHash == "old-hash")
        verify(users, never()).save(anyOf(User::class.java))
        verify(invites, never()).save(anyOf(InvitationKey::class.java))
    }

    @Test
    fun `an existing doctor is refused even with a valid code`() {
        val user = existing()
        accountByEmail(user)
        `when`(userRoles.existsByUserIdAndRole(user.id, Role.DOCTOR)).thenReturn(true)
        `when`(emailOtpService.check(email, "123456", registration)).thenReturn(OtpCheck.VALID)

        register(""","emailOtp":"123456"""")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DOCTOR_EXISTS"))

        assert(user.passwordHash == "old-hash")
    }

    @Test
    fun `an existing patient without proof is refused before anything changes`() {
        val user = existing()
        accountByEmail(user)

        register()
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("OWNERSHIP_REQUIRED"))

        assert(user.passwordHash == "old-hash")
        verify(users, never()).save(anyOf(User::class.java))
        verify(doctors, never()).save(anyOf(DoctorProfile::class.java))
    }

    @Test
    fun `an existing patient with a wrong current password is refused`() {
        val user = existing()
        accountByEmail(user)
        `when`(encoder.matches("guess", "old-hash")).thenReturn(false)

        register(""","currentPassword":"guess"""")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("OWNERSHIP_REQUIRED"))

        assert(user.passwordHash == "old-hash")
    }

    @Test
    fun `an existing patient with the e-mail code becomes a doctor`() {
        val user = existing()
        accountByEmail(user)
        `when`(emailOtpService.check(email, "123456", registration)).thenReturn(OtpCheck.VALID)

        register(""","emailOtp":"123456"""").andExpect(status().isOk).andExpect(jsonPath("$.token").value("token"))

        assert(user.passwordHash == "new-hash")
        verify(doctors).save(anyOf(DoctorProfile::class.java))
    }

    @Test
    fun `an existing patient with the current password becomes a doctor`() {
        val user = existing()
        accountByEmail(user)
        `when`(encoder.matches("Current1!", "old-hash")).thenReturn(true)

        register(""","currentPassword":"Current1!"""").andExpect(status().isOk)

        assert(user.passwordHash == "new-hash")
    }

    @Test
    fun `a code for another address proves nothing about an account found by phone`() {
        // Found by phone with no e-mail on file: the code proves the typed address, not this account.
        val user = existing(mail = null).also { it.phone = "+998901234567" }
        `when`(users.findByPhone("+998901234567")).thenReturn(Optional.of(user))
        `when`(emailOtpService.check(email, "123456", registration)).thenReturn(OtpCheck.VALID)

        register(""","phone":"+998901234567","emailOtp":"123456"""")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("OWNERSHIP_REQUIRED"))

        assert(user.passwordHash == "old-hash")
    }

    @Test
    fun `a wrong code is refused before any account lookup`() {
        `when`(emailOtpService.check(email, "000000", registration)).thenReturn(OtpCheck.INVALID)

        register(""","emailOtp":"000000"""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("EMAIL_OTP_INVALID"))

        verifyNoInteractions(users, userSessions, jwt)
    }

    @Test
    fun `an expired code says so`() {
        `when`(emailOtpService.check(email, "111111", registration)).thenReturn(OtpCheck.EXPIRED)

        register(""","emailOtp":"111111"""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("EMAIL_OTP_EXPIRED"))
    }

    @Test
    fun `a new address registers without a code, as store builds do`() {
        register().andExpect(status().isOk).andExpect(jsonPath("$.token").value("token"))

        verify(doctors).save(anyOf(DoctorProfile::class.java))
        verifyNoInteractions(emailOtpService)
    }

    @Test
    fun `clinic staff registration needs the same proof for an existing account`() {
        val user = existing()
        accountByEmail(user)
        val clinic = Clinic(id = 3L, name = "Clinic")
        `when`(invites.findByKeyCode("STAFF-1")).thenReturn(
            InvitationKey(
                keyCode = "STAFF-1",
                purpose = InvitationKey.PURPOSE_CLINIC_RECEPTIONIST_INVITE,
                clinic = clinic,
                membershipRole = ClinicMembership.MembershipRole.RECEPTIONIST,
            ),
        )
        `when`(clinics.findById(3L)).thenReturn(Optional.of(clinic))

        mockMvc.perform(
            post("/api/auth/register-clinic-staff").contentType(MediaType.APPLICATION_JSON).content(
                """{"firstName":"A","lastName":"B","email":"$email","password":"$password","key":"STAFF-1"}""",
            ),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("OWNERSHIP_REQUIRED"))

        assert(user.passwordHash == "old-hash")
    }

    @Test
    fun `check-existing-doctor matches the e-mail whatever its case`() {
        val user = existing(mail = "Doctor@Example.uz")
        `when`(users.findByEmailIgnoreCase("DOCTOR@example.uz")).thenReturn(Optional.of(user))
        `when`(userRoles.existsByUserIdAndRole(user.id, Role.DOCTOR)).thenReturn(true)
        `when`(doctors.findByUserId(user.id)).thenReturn(
            Optional.of(DoctorProfile(user = user, firstName = "Aziza", lastName = "K")),
        )

        mockMvc.perform(
            post("/api/auth/check-existing-doctor").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"DOCTOR@example.uz"}"""),
        ).andExpect(status().isOk).andExpect(jsonPath("$.firstName").value("Aziza"))
    }
}
