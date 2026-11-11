package com.shifa.auth.application

import com.shifa.appointments.application.Appointments
import com.shifa.appointments.domain.Appointment
import com.shifa.auth.domain.EmailVerificationCode
import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import com.shifa.auth.infrastructure.UserRepository
import com.shifa.clinics.application.ClinicMemberships
import com.shifa.doctors.application.DoctorProfiles
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.notifications.application.DoctorPushTokens
import com.shifa.payments.application.SubscriptionService
import com.shifa.shared.CodedStatusException
import com.shifa.shared.ErrorCode
import java.time.Instant
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class DoctorAccountDeletionServiceTest {

    private val users = mock(UserRepository::class.java)
    private val doctors = mock(DoctorProfiles::class.java)
    private val appointments = mock(Appointments::class.java)
    private val subscriptions = mock(SubscriptionService::class.java)
    private val memberships = mock(ClinicMemberships::class.java)
    private val pushTokens = mock(DoctorPushTokens::class.java)
    private val userManagement = mock(UserManagementService::class.java)
    private val emailOtp = mock(EmailOtpService::class.java)
    private val userRoles = mock(UserRoles::class.java)
    private val service = DoctorAccountDeletionService(
        users, doctors, appointments, subscriptions, memberships, pushTokens, userManagement, emailOtp, userRoles,
    )

    private val purpose = EmailVerificationCode.PURPOSE_ACCOUNT_DELETION
    private val user = User(id = 7L, email = "Aziza@Example.uz", phone = "+998901234567", passwordHash = "h", role = Role.DOCTOR)
    private val doctor = DoctorProfile(id = 3L, user = user, firstName = "Aziza", lastName = "Karimova").also {
        it.biography = "Dentist"
        it.fcmToken = "legacy"
    }

    init {
        `when`(doctors.findById(3L)).thenReturn(Optional.of(doctor))
        `when`(userRoles.rolesOf(user)).thenReturn(setOf(Role.DOCTOR)) // a doctor-only account unless a test says otherwise
        `when`(users.save(user)).thenReturn(user)
    }

    private fun upcoming(status: Appointment.Status, count: Long) {
        `when`(
            appointments.countByDoctor_IdAndStartAtBetweenAndStatus(
                ArgumentMatchers.eq(3L),
                ArgumentMatchers.any(Instant::class.java) ?: Instant.EPOCH,
                ArgumentMatchers.any(Instant::class.java) ?: Instant.EPOCH,
                ArgumentMatchers.eq(status) ?: status,
            ),
        ).thenReturn(count)
    }

    @Test
    fun `upcoming appointments block the request and no code is sent`() {
        upcoming(Appointment.Status.CONFIRMED, 2)

        val e = assertThrows<CodedStatusException> { service.request(3L) }

        assertEquals(ErrorCode.HAS_ACTIVE_OBLIGATIONS, e.code)
        assertEquals(listOf("UPCOMING_APPOINTMENTS"), e.details)
        verify(emailOtp, never()).sendCode(ArgumentMatchers.anyString(), ArgumentMatchers.anyString())
    }

    @Test
    fun `a request e-mails the code to the account's address`() {
        `when`(emailOtp.sendCode("aziza@example.uz", purpose)).thenReturn(true)

        val r = service.request(3L)

        assertEquals("a***@example.uz", r.maskedEmail)
        assertEquals(600L, r.expiresInSeconds)
    }

    @Test
    fun `a wrong code changes nothing`() {
        `when`(emailOtp.check("aziza@example.uz", "000000", purpose)).thenReturn(EmailOtpService.OtpCheck.INVALID)

        val e = assertThrows<CodedStatusException> { service.confirm(3L, "000000") }

        assertEquals(ErrorCode.EMAIL_OTP_INVALID, e.code)
        assertEquals("Aziza@Example.uz", user.email)
        verify(userManagement, never()).forceLogout(7L, null)
    }

    @Test
    fun `the right code closes and anonymises the account but keeps the profile row`() {
        `when`(emailOtp.check("aziza@example.uz", "123456", purpose)).thenReturn(EmailOtpService.OtpCheck.VALID)
        `when`(pushTokens.tokensOf(3L)).thenReturn(listOf("dev-1"))

        service.confirm(3L, "123456")

        verify(userManagement).forceLogout(7L, null)
        verify(pushTokens).unregister(3L, "dev-1")
        assertFalse(user.enabled)
        assertEquals(User.AccountStatus.DELETED, user.accountStatus)
        assertNull(user.email)
        assertNull(user.phone)
        assertEquals("Deleted", doctor.firstName)
        assertEquals("Doctor", doctor.lastName)
        assertNull(doctor.biography)
        assertNull(doctor.fcmToken)
        verify(doctors).save(doctor)
        verify(doctors, never()).delete(doctor)
    }

    @Test
    fun `a doctor who is also a patient loses only the doctor side, and the patient app keeps working`() {
        `when`(emailOtp.check("aziza@example.uz", "123456", purpose)).thenReturn(EmailOtpService.OtpCheck.VALID)
        `when`(userRoles.rolesOf(user)).thenReturn(setOf(Role.DOCTOR, Role.PATIENT), setOf(Role.PATIENT))
        user.patientPasswordHash = "patient-hash"

        service.confirm(3L, "123456")

        verify(userRoles).remove(user, Role.DOCTOR)
        assertTrue(user.enabled)
        assertEquals(User.AccountStatus.ACTIVE, user.accountStatus)
        assertEquals("Aziza@Example.uz", user.email)
        assertEquals("+998901234567", user.phone)
        assertEquals("patient-hash", user.passwordHash) // the patient app's own password is now the only one
        assertNull(user.patientPasswordHash)
        assertEquals("Deleted", doctor.firstName) // the doctor side is anonymised as before
        verify(userManagement).forceLogout(7L, null)
    }

    @Test
    fun `a doctor who is also staff and a patient keeps the staff password`() {
        `when`(emailOtp.check("aziza@example.uz", "123456", purpose)).thenReturn(EmailOtpService.OtpCheck.VALID)
        `when`(userRoles.rolesOf(user))
            .thenReturn(setOf(Role.DOCTOR, Role.PATIENT, Role.CLINIC_STAFF), setOf(Role.PATIENT, Role.CLINIC_STAFF))
        user.patientPasswordHash = "patient-hash"

        service.confirm(3L, "123456")

        assertEquals("h", user.passwordHash) // staff still signs in with it
        assertEquals("patient-hash", user.patientPasswordHash)
        assertNull(doctor.practiceClinic)
    }
}
