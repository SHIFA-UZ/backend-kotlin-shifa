package com.shifa.auth.application

import com.shifa.appointments.domain.Appointment
import com.shifa.auth.domain.EmailVerificationCode
import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import com.shifa.auth.infrastructure.UserRepository
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.payments.domain.DoctorSubscriptionStatus
import com.shifa.shared.CodedStatusException
import com.shifa.shared.ErrorCode
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

/**
 * A doctor deleting their own account (App Store 5.1.1(v), Google Play account deletion). Unlike the admin
 * [UserDeletionService] nothing clinical is removed: appointments, notes, forms and documents stay with the patients
 * and clinics that must keep them, and the doctor is anonymised in them ("Deleted Doctor"). Sign-in, every session,
 * every push device and the public listing (disabled users are never listed) end at once.
 */
@Service
class DoctorAccountDeletionService(
    private val users: UserRepository,
    private val doctors: com.shifa.doctors.application.DoctorProfiles,
    private val appointments: com.shifa.appointments.application.Appointments,
    private val subscriptions: com.shifa.payments.application.SubscriptionService,
    private val memberships: com.shifa.clinics.application.ClinicMemberships,
    private val pushTokens: com.shifa.notifications.application.DoctorPushTokens,
    private val userManagement: UserManagementService,
    private val emailOtp: EmailOtpService,
    private val userRoles: UserRoles,
) {
    private val log = LoggerFactory.getLogger(DoctorAccountDeletionService::class.java)

    data class Requested(val maskedEmail: String, val expiresInSeconds: Long)

    /**
     * Step 1: refuses while the doctor has obligations (409 `HAS_ACTIVE_OBLIGATIONS`, `details` naming each), else
     * e-mails a code to the account's address. Nothing changes yet.
     */
    @Transactional // sends (writes) the code: not read-only
    fun request(doctorProfileId: Long): Requested {
        val doctor = doctor(doctorProfileId)
        requireNoObligations(doctor)
        val email = accountEmail(doctor.user)
        if (!emailOtp.sendCode(email, EmailVerificationCode.PURPOSE_ACCOUNT_DELETION)) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many verification requests. Please try again later.")
        }
        return Requested(maskEmail(email), CODE_LIFETIME.seconds)
    }

    /** Step 2: the e-mailed code confirms; the account is anonymised and closed in one transaction. */
    @Transactional
    fun confirm(doctorProfileId: Long, code: String) {
        val doctor = doctor(doctorProfileId)
        val user = doctor.user
        if (user.accountStatus == User.AccountStatus.DELETED) return
        requireNoObligations(doctor)
        when (emailOtp.check(accountEmail(user), code.trim(), EmailVerificationCode.PURPOSE_ACCOUNT_DELETION)) {
            EmailOtpService.OtpCheck.VALID -> Unit
            EmailOtpService.OtpCheck.EXPIRED -> throw CodedStatusException(
                HttpStatus.BAD_REQUEST, "The verification code has expired. Request a new one.", ErrorCode.EMAIL_OTP_EXPIRED,
            )
            EmailOtpService.OtpCheck.INVALID -> throw CodedStatusException(
                HttpStatus.BAD_REQUEST, "Invalid verification code.", ErrorCode.EMAIL_OTP_INVALID,
            )
        }

        userManagement.forceLogout(user.id, revokedBy = null)
        pushTokens.tokensOf(doctor.id).forEach { pushTokens.unregister(doctor.id, it) }
        // An account that is also a patient or staff (one users row, several roles) loses only its doctor side: the
        // DOCTOR role, the doctor's memberships and the profile below. Before, the whole account was closed, so the
        // patient app stopped working too (the user's report, 2026-11-11).
        val keepAccount = userRoles.rolesOf(user).any { it != Role.DOCTOR }
        memberships.findByUserIdAndActiveTrue(user.id)
            .filter { !keepAccount || it.membershipRole != com.shifa.clinics.domain.ClinicMembership.MembershipRole.STAFF }
            .forEach { it.active = false; memberships.save(it) }

        val now = OffsetDateTime.now()
        if (keepAccount) {
            userRoles.remove(user, Role.DOCTOR)
            // Only the patient side left: its own password, if it had one, becomes the account's only password. A
            // staff side keeps the shared password it signs in with.
            if (userRoles.rolesOf(user) == setOf(Role.PATIENT)) {
                user.patientPasswordHash?.let { user.passwordHash = it }
                user.patientPasswordHash = null
            }
            users.save(user)
            // The profile stays (user_id can't be detached) and a doctor who registers again gets it back: without
            // its clinic, which only a new invitation grants.
            doctor.practiceClinic = null
        } else {
            user.phoneOriginalHash = user.phone?.let(::sha256Hex)
            user.emailOriginalHash = user.email?.let { sha256Hex(it.trim().lowercase()) }
            user.phone = null
            user.email = null
            user.staffFirstName = null
            user.staffLastName = null
            user.staffPhotoUrl = null
            user.enabled = false
            user.accountStatus = User.AccountStatus.DELETED
            user.deletionRequestedAt = user.deletionRequestedAt ?: now
            user.deletedAt = now
            users.save(user)
        }

        doctor.firstName = "Deleted"
        doctor.lastName = "Doctor"
        doctor.avatarUrl = null
        doctor.dob = null
        doctor.gender = null
        doctor.address = null
        doctor.biography = null
        doctor.certificates = null
        doctor.telegram = null
        doctor.instagram = null
        doctor.latitude = null
        doctor.longitude = null
        doctor.locationStreetAddress = null
        doctor.locationPostalCode = null
        doctor.fcmToken = null
        doctors.save(doctor)
        log.info("DOCTOR_DELETE: account closed and anonymised (doctorProfileId={})", doctor.id)
    }

    /** What still binds the doctor: upcoming bookings and a paid subscription that renews. */
    @Transactional(readOnly = true)
    fun obligations(doctor: DoctorProfile): List<String> {
        val now = Instant.now()
        val horizon = now.plus(Duration.ofDays(HORIZON_DAYS))
        val upcoming = UPCOMING.sumOf { appointments.countByDoctor_IdAndStartAtBetweenAndStatus(doctor.id, now, horizon, it) } +
            // A visit under way (started in the past week and not ended) binds as much as a future one.
            appointments.countByDoctor_IdAndStartAtBetweenAndStatus(
                doctor.id,
                now.minus(Duration.ofDays(IN_PROGRESS_LOOKBACK_DAYS)),
                horizon,
                Appointment.Status.IN_PROGRESS,
            )
        val subscription = subscriptions.getDoctorSubscription(doctor.id)
        val renews = subscription != null &&
            subscription.status in setOf(DoctorSubscriptionStatus.ACTIVE, DoctorSubscriptionStatus.PAST_DUE) &&
            !subscription.externalSubscriptionId.isNullOrBlank() &&
            !subscription.cancelAtPeriodEnd
        return listOfNotNull(
            "UPCOMING_APPOINTMENTS".takeIf { upcoming > 0 },
            "ACTIVE_SUBSCRIPTION".takeIf { renews },
        )
    }

    private fun requireNoObligations(doctor: DoctorProfile) {
        val open = obligations(doctor)
        if (open.isEmpty()) return
        throw CodedStatusException(
            HttpStatus.CONFLICT,
            "Cancel or hand over upcoming appointments and cancel the paid subscription before deleting the account.",
            ErrorCode.HAS_ACTIVE_OBLIGATIONS,
            open,
        )
    }

    private fun doctor(doctorProfileId: Long): DoctorProfile =
        doctors.findById(doctorProfileId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found") }

    private fun accountEmail(user: User): String =
        user.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Add an email address to the account first.")

    private fun maskEmail(email: String): String {
        val at = email.indexOf('@')
        return if (at <= 1) "***" else email.take(1) + "***" + email.substring(at)
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        val UPCOMING = listOf(Appointment.Status.REQUESTED, Appointment.Status.CONFIRMED, Appointment.Status.PENDING_PAYMENT)
        const val HORIZON_DAYS = 3650L
        const val IN_PROGRESS_LOOKBACK_DAYS = 7L
        val CODE_LIFETIME: Duration = Duration.ofMinutes(10)
    }
}
