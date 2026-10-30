package com.shifa.auth.api

import com.shifa.auth.api.dto.AdminLoginOtpSentResponse
import com.shifa.auth.api.dto.AdminVerifyLoginOtpRequest
import com.shifa.auth.api.dto.ChangePasswordRequest
import com.shifa.auth.api.dto.CheckExistingDoctorRequest
import com.shifa.auth.api.dto.CheckExistingDoctorResponse
import com.shifa.auth.api.dto.CheckExistingPatientRequest
import com.shifa.auth.api.dto.CheckExistingPatientResponse
import com.shifa.auth.api.dto.CheckIdentifierRequest
import com.shifa.auth.api.dto.CheckIdentifierResponse
import com.shifa.auth.api.dto.CreatePatientForDoctorRequest
import com.shifa.auth.api.dto.ForgotPasswordResetRequest
import com.shifa.auth.api.dto.LoginRequest
import com.shifa.auth.api.dto.RegisterClinicStaffRequest
import com.shifa.auth.api.dto.RegisterPatientRequest
import com.shifa.auth.api.dto.RegisterRequest
import com.shifa.auth.api.dto.SendEmailOtpRequest
import com.shifa.auth.api.dto.SendEmailOtpResponse
import com.shifa.auth.api.dto.SendForgotPasswordOtpRequest
import com.shifa.auth.api.dto.SendForgotPasswordOtpResponse
import com.shifa.auth.api.dto.SendLoginOtpRequest
import com.shifa.auth.api.dto.SendOtpChannelResponse
import com.shifa.auth.api.dto.SendSmsOtpRequest
import com.shifa.auth.api.dto.VerifyEmailOtpRequest
import com.shifa.auth.api.dto.VerifyKeyRequest
import com.shifa.auth.api.dto.VerifyKeyResponse
import com.shifa.auth.domain.EmailVerificationCode
import com.shifa.auth.domain.Role
import com.shifa.auth.domain.SmsVerificationCode
import com.shifa.auth.domain.User
import com.shifa.auth.domain.UserRole
import com.shifa.auth.domain.UserSession
import com.shifa.auth.infrastructure.FirebaseAuthService
import com.shifa.clinics.domain.Clinic
import com.shifa.clinics.domain.ClinicMembership
import com.shifa.clinics.domain.InvitationKey
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.patients.domain.PatientProfile
import com.shifa.shared.security.JwtService
import com.shifa.shared.security.PasswordPolicy
import com.shifa.shared.util.PhoneNormalizer
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException


@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val users: com.shifa.auth.application.Users,
    private val invites: com.shifa.clinics.application.InvitationKeys,
    private val doctors: com.shifa.doctors.application.DoctorProfiles,
    private val patients: com.shifa.patients.application.PatientProfiles,
    private val userSessions: com.shifa.auth.application.UserSessions,
    private val encoder: PasswordEncoder,
    private val jwt: JwtService,
    private val userActivityService: com.shifa.auth.application.UserActivityService,
    private val userManagementService: com.shifa.auth.application.UserManagementService,
    private val userRoles: com.shifa.auth.application.UserRoles,
    private val firebaseAuthService: com.shifa.auth.infrastructure.FirebaseAuthService,
    private val emailOtpService: com.shifa.auth.application.EmailOtpService,
    private val smsOtpService: com.shifa.auth.application.SmsOtpService,
    private val clinicMemberships: com.shifa.clinics.application.ClinicMemberships,
    private val clinics: com.shifa.clinics.application.Clinics,
) {
    private val log = LoggerFactory.getLogger(AuthController::class.java)
    // ---------- VerifyKey ----------

    /**
     * VerifyKeyScreen calls this to check if the one-time key is valid & not consumed.
     * UI: show "Next" if valid, else show error.
     */
    @PostMapping("/verify-key")
    fun verifyKey(@RequestBody @Valid req: VerifyKeyRequest): VerifyKeyResponse {
        val raw = invites.findByKeyCode(req.key.trim())
        val ok = raw != null && raw.isValid()
        if (!ok || raw == null) {
            return VerifyKeyResponse(valid = false)
        }
        val cid = raw.clinic?.id
        val clinicName =
            cid?.let { id -> clinics.findById(id).map { it.name }.orElse(null) }
                ?: raw.clinic?.name
        return VerifyKeyResponse(
            valid = true,
            purpose = raw.purpose,
            clinicId = cid,
            clinicName = clinicName?.takeIf { it.isNotBlank() },
            emailSentTo = raw.emailSentTo?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    // ---------- Check existing patient (for doctor registration UX) ----------

    /**
     * Doctor app calls this after user enters first name, last name, email, and optional phone.
     * If a user exists with this phone (when provided) or email (e.g. existing patient account), returns found=true and their details
     * so the UI can show "There is already a patient... we are creating a doctor account" and hide extra fields.
     */
    @PostMapping("/check-existing-patient")
    fun checkExistingPatient(@RequestBody @Valid req: CheckExistingPatientRequest): CheckExistingPatientResponse {
        val phoneRaw = req.phone?.trim()?.takeIf { it.isNotBlank() }
        val phoneNorm = phoneRaw?.let { PhoneNormalizer.normalize(it) }
        val emailNorm = req.email.trim().lowercase()
        val existingUser = phoneRaw?.let { users.findByPhone(it).orElse(null) }
            ?: phoneNorm?.let { users.findByPhone(it).orElse(null) }
            ?: users.findByEmailIgnoreCase(emailNorm).orElse(null)
            ?: return CheckExistingPatientResponse(found = false)
        val patientProfile = patients.findByUserId(existingUser.id).orElse(null)
        val fullName = patientProfile?.fullName?.takeIf { it.isNotBlank() }
            ?: "${req.firstName.trim()} ${req.lastName.trim()}".trim()
        return CheckExistingPatientResponse(
            found = true,
            fullName = fullName,
            photoUrl = patientProfile?.photoUrl?.takeIf { it.isNotBlank() },
            email = existingUser.email?.takeIf { it.isNotBlank() }
        )
    }

    // ---------- Check existing doctor (for patient app create-account) ----------

    /**
     * Patient app calls this after user enters phone and/or email on create-account.
     * If a DOCTOR user exists with this phone/email, returns their name so the app can offer "Create patient account for this doctor".
     */
    @PostMapping("/check-existing-doctor")
    fun checkExistingDoctor(@RequestBody @Valid req: CheckExistingDoctorRequest): CheckExistingDoctorResponse {
        val phoneTrimmed = req.phone?.trim()?.takeIf { it.isNotBlank() }
        val emailTrimmed = req.email?.trim()?.takeIf { it.isNotBlank() }
        if (phoneTrimmed == null && emailTrimmed == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone or email required")
        }
        val phoneNorm = phoneTrimmed?.let { PhoneNormalizer.normalize(it) }
        val user = phoneTrimmed?.let { users.findByPhone(it).orElse(null) }
            ?: phoneNorm?.let { users.findByPhone(it).orElse(null) }
            ?: emailTrimmed?.let { users.findByEmail(it).orElse(null) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No account found")
        if (!userRoles.existsByUserIdAndRole(user.id, Role.DOCTOR)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No doctor account found")
        }
        val doctorProfile = doctors.findByUserId(user.id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor profile not found")
        }
        return CheckExistingDoctorResponse(
            firstName = doctorProfile.firstName,
            lastName = doctorProfile.lastName
        )
    }

    // ---------- Check identifier for patient app (phone/email -> doctor | patient | none) ----------

    /**
     * Patient app create-account: check if phone/email is already a doctor or patient.
     */
    @PostMapping("/check-identifier")
    fun checkIdentifier(@RequestBody @Valid req: CheckIdentifierRequest): CheckIdentifierResponse {
        val phoneTrimmed = req.phone?.trim()?.takeIf { it.isNotBlank() }
        val emailTrimmed = req.email?.trim()?.takeIf { it.isNotBlank() }
        if (phoneTrimmed == null && emailTrimmed == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone or email required")
        }
        val phoneNorm = phoneTrimmed?.let { PhoneNormalizer.normalize(it) }
        val user = phoneTrimmed?.let { users.findByPhone(it).orElse(null) }
            ?: phoneNorm?.let { users.findByPhone(it).orElse(null) }
            ?: emailTrimmed?.let { users.findByEmail(it).orElse(null) }
            ?: return CheckIdentifierResponse(type = "none")
        if (userRoles.existsByUserIdAndRole(user.id, Role.DOCTOR)) {
            val doc = doctors.findByUserId(user.id).orElse(null)
            return CheckIdentifierResponse(
                type = "doctor",
                firstName = doc?.firstName,
                lastName = doc?.lastName
            )
        }
        if (userRoles.existsByUserIdAndRole(user.id, Role.PATIENT)) {
            val pat = patients.findByUserId(user.id).orElse(null)
            val name = pat?.fullName?.split(" ", limit = 2)
            return CheckIdentifierResponse(
                type = "patient",
                firstName = name?.getOrNull(0),
                lastName = name?.getOrNull(1)
            )
        }
        return CheckIdentifierResponse(type = "none")
    }

    // ---------- Send email OTP (generic – registration, doctor-to-patient, etc.) ----------

    /**
     * Sends a 6-digit OTP to the given email via Brevo SMTP.
     * Purpose defaults to REGISTRATION for backward compatibility.
     */
    @PostMapping("/send-email-otp")
    fun sendEmailOtp(@RequestBody @Valid req: SendEmailOtpRequest): SendEmailOtpResponse {
        val email = req.email.trim().lowercase()
        val purpose = req.purpose?.uppercase()
            ?: com.shifa.auth.domain.EmailVerificationCode.PURPOSE_REGISTRATION
        try {
            if (!emailOtpService.sendCode(email, purpose)) {
                throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many verification requests. Please try again later.")
            }
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            log.error("send-email-otp failed for {}: {}", email.take(3) + "***", e.message)
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not send verification email. Please try again later.")
        }
        return SendEmailOtpResponse(sent = true)
    }

    // ---------- Send SMS OTP (patient registration / forgot-password for UZ numbers) ----------

    /**
     * Sends a 6-digit OTP via DevSMS to an Uzbek mobile number (+998).
     * Purpose defaults to REGISTRATION.
     * On SMS provider failure, optionally falls back to [SendSmsOtpRequest.fallbackEmail].
     */
    @PostMapping("/send-sms-otp")
    fun sendSmsOtp(@RequestBody @Valid req: SendSmsOtpRequest): SendOtpChannelResponse {
        val normalized = PhoneNormalizer.normalize(req.phone.trim())
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid phone number")
        if (!PhoneNormalizer.isUzbekMobile(normalized)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "SMS verification is only available for Uzbek phone numbers. Please use email."
            )
        }
        val purpose = req.purpose?.uppercase()
            ?: com.shifa.auth.domain.SmsVerificationCode.PURPOSE_REGISTRATION
        val emailPurpose = smsPurposeToEmailPurpose(purpose)
        when (val result = smsOtpService.sendCode(normalized, purpose)) {
            is com.shifa.auth.application.SmsOtpSendResult.Success ->
                return SendOtpChannelResponse(channel = "sms")
            is com.shifa.auth.application.SmsOtpSendResult.Failure -> {
                val fallbackEmail = req.fallbackEmail?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                if (shouldFallbackSmsToEmail(result.reason) && fallbackEmail != null) {
                    return sendEmailOtpChannelOrThrow(fallbackEmail, emailPurpose, smsFallback = true)
                }
                throwSmsOtpFailure(result)
            }
        }
    }

    // ---------- Send login OTP (doctor email OTP sign-in) ----------

    /**
     * Doctor app: send a sign-in OTP to the doctor's registered email.
     * Only works for enabled users with the DOCTOR role.
     */
    @PostMapping("/send-login-otp")
    fun sendLoginOtp(@RequestBody @Valid req: SendLoginOtpRequest): SendEmailOtpResponse {
        val email = req.email.trim().lowercase()
        val user = users.findByEmail(email).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No account found with this email")
        if (!user.enabled) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Account is disabled")
        }
        if (user.isLocked()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Account is locked")
        }
        if (!userRoles.existsByUserIdAndRole(user.id, Role.DOCTOR)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access restricted to doctors")
        }
        if (!emailOtpService.sendCode(email, com.shifa.auth.domain.EmailVerificationCode.PURPOSE_LOGIN)) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many verification requests. Please try again later.")
        }
        return SendEmailOtpResponse(sent = true)
    }

    // ---------- Verify email OTP → JWT (doctor email OTP sign-in) ----------

    /**
     * Doctor app: verify the email OTP code and return a JWT.
     * Replaces the Firebase phone OTP verify endpoint for doctor login.
     */
    @PostMapping("/verify-email-otp")
    fun verifyEmailOtp(@RequestBody @Valid req: VerifyEmailOtpRequest): TokenResponse {
        val email = req.email.trim().lowercase()
        if (!emailOtpService.verify(email, req.code.trim(), com.shifa.auth.domain.EmailVerificationCode.PURPOSE_LOGIN)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired verification code")
        }
        val user = users.findByEmail(email).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        if (!user.enabled) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Account is disabled")
        }
        if (!userRoles.existsByUserIdAndRole(user.id, Role.DOCTOR)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access restricted to doctors")
        }
        user.emailVerified = true
        users.save(user)
        val principal = user.email ?: user.phone!!
        val tokenResult = jwt.generate(user.id, principal, Role.DOCTOR.name)
        userSessions.save(
            UserSession(
                user = user,
                tokenJti = tokenResult.jti,
                expiresAt = OffsetDateTime.ofInstant(tokenResult.expiresAt, ZoneOffset.UTC)
            )
        )
        userManagementService.updateLastLogin(user.id)
        return TokenResponse(token = tokenResult.token, forcePasswordReset = user.forcePasswordReset)
    }

    // ---------- Send forgot-password OTP ----------

    @PostMapping("/send-forgot-password-otp")
    fun sendForgotPasswordOtp(
        @RequestBody @Valid req: SendForgotPasswordOtpRequest,
        @RequestParam(required = false) app: String?,
        request: jakarta.servlet.http.HttpServletRequest
    ): SendForgotPasswordOtpResponse {
        val id = req.identifier.trim()
        val user = users.findByEmailIgnoreCase(id).orElse(null)
            ?: users.findByPhone(id).orElse(null)
            ?: PhoneNormalizer.normalize(id)?.let { users.findByPhone(it).orElse(null) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No account found")
        val appType = req.app?.trim()?.lowercase()
            ?: app?.trim()?.lowercase()
            ?: request.getHeader("X-App")?.trim()?.lowercase()
        ensureForgotPasswordAccountForApp(user, appType)
        if (!user.enabled) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Account is disabled")
        }
        val email = user.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (email != null) {
            sendEmailOtpChannelOrThrow(
                email,
                com.shifa.auth.domain.EmailVerificationCode.PURPOSE_FORGOT_PASSWORD,
            )
            return SendForgotPasswordOtpResponse(channel = "email")
        }
        val phone = user.phone
        if (phone != null && PhoneNormalizer.isUzbekMobile(phone)) {
            when (val smsResult = smsOtpService.sendCode(
                phone,
                com.shifa.auth.domain.SmsVerificationCode.PURPOSE_FORGOT_PASSWORD,
            )) {
                is com.shifa.auth.application.SmsOtpSendResult.Success ->
                    return SendForgotPasswordOtpResponse(channel = "sms")
                is com.shifa.auth.application.SmsOtpSendResult.Failure -> {
                    val recoveredEmail = user.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                    if (shouldFallbackSmsToEmail(smsResult.reason) && recoveredEmail != null) {
                        sendEmailOtpChannelOrThrow(
                            recoveredEmail,
                            com.shifa.auth.domain.EmailVerificationCode.PURPOSE_FORGOT_PASSWORD,
                            smsFallback = true,
                        )
                        return SendForgotPasswordOtpResponse(channel = "email")
                    }
                    throwSmsOtpFailure(smsResult)
                }
            }
        }
        throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "No email address on file. Please contact support."
        )
    }

    // ---------- Create patient account for existing doctor ----------

    /**
     * Patient app: after doctor confirms and verifies an OTP, create PATIENT role + patient profile.
     * They use their doctor password to log in.
     *
     * Every path must prove control of the identifier: email requires [CreatePatientForDoctorRequest.emailOtp],
     * phone-only requires [CreatePatientForDoctorRequest.smsOtp]. Without this, knowing a doctor's phone
     * number was enough to obtain a PATIENT token for their account.
     */
    @PostMapping("/create-patient-for-doctor")
    fun createPatientForDoctor(@RequestBody @Valid req: CreatePatientForDoctorRequest): TokenResponse {
        val phoneTrimmed = req.phone?.trim()?.takeIf { it.isNotBlank() }
        val emailTrimmed = req.email?.trim()?.takeIf { it.isNotBlank() }
        if (phoneTrimmed == null && emailTrimmed == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone or email required")
        }
        if (emailTrimmed != null && req.emailOtp.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Email OTP required when email is provided")
        }
        if (emailTrimmed != null && req.emailOtp != null) {
            if (!emailOtpService.verify(emailTrimmed, req.emailOtp)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired email verification code")
            }
        }
        val phoneNorm = phoneTrimmed?.let { PhoneNormalizer.normalize(it) }
        if (phoneTrimmed != null && phoneNorm == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid phone number")
        }
        if (emailTrimmed == null) {
            // Phone-only request: the SMS code is the only proof of ownership.
            if (req.smsOtp.isNullOrBlank()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "SMS verification code required")
            }
            if (!smsOtpService.verify(requireNotNull(phoneNorm), req.smsOtp)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired SMS verification code")
            }
        }
        // The account is selected ONLY by the identifier that was just verified. Looking up by phone
        // after verifying an email would let anyone pair a victim's phone with their own email + code.
        val user = if (emailTrimmed != null) {
            // Same normalisation as EmailOtpService.verify, so the account found is the one the code was issued for.
            val emailCanonical = emailTrimmed.lowercase()
            val byEmail = users.findByEmail(emailTrimmed).orElse(null)
                ?: try {
                    users.findByEmailIgnoreCase(emailTrimmed).orElse(null)
                } catch (_: org.springframework.dao.IncorrectResultSizeDataAccessException) {
                    null // case-variant duplicates exist: ambiguous, treat as not found rather than 500
                }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No account found")
            if (byEmail.email?.trim()?.lowercase() != emailCanonical) {
                // SQL case-folding matched something Kotlin lowercase() does not consider the same address.
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "No account found")
            }
            val storedPhoneNorm = byEmail.phone?.let { PhoneNormalizer.normalize(it) ?: it }
            if (phoneNorm != null && storedPhoneNorm != null && storedPhoneNorm != phoneNorm && byEmail.phone != phoneTrimmed) {
                // A phone was sent that belongs to a different account than the verified email.
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "No account found")
            }
            byEmail
        } else {
            users.findByPhone(requireNotNull(phoneTrimmed)).orElse(null)
                ?: users.findByPhone(requireNotNull(phoneNorm)).orElse(null)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No account found")
        }
        if (!user.enabled) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Account is disabled")
        }
        if (!userRoles.existsByUserIdAndRole(user.id, Role.DOCTOR)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not a doctor account")
        }
        if (userRoles.existsByUserIdAndRole(user.id, Role.PATIENT)) {
            // Already has patient account - just return token
            val principal = user.email ?: user.phone!!
            val tokenResult = jwt.generate(user.id, principal, Role.PATIENT.name)
            userSessions.save(
                UserSession(
                    user = user,
                    tokenJti = tokenResult.jti,
                    expiresAt = OffsetDateTime.ofInstant(tokenResult.expiresAt, ZoneOffset.UTC)
                )
            )
            return TokenResponse(token = tokenResult.token, forcePasswordReset = user.forcePasswordReset)
        }
        userRoles.save(com.shifa.auth.domain.UserRole(user = user, role = Role.PATIENT))
        val doctorProfile = doctors.findByUserId(user.id).orElse(null)
        val fullName = doctorProfile?.let { "${it.firstName} ${it.lastName}".trim() }
            ?: (user.email ?: user.phone ?: "Patient")
        val profilePhoneNorm = user.phone?.let { PhoneNormalizer.normalize(it) }
        val patientProfile = com.shifa.patients.domain.PatientProfile(
            fullName = fullName,
            phone = user.phone,
            phoneNormalized = profilePhoneNorm ?: phoneNorm,
            email = user.email,
            documents = mutableListOf()
        )
        patientProfile.user = user
        patients.save(patientProfile)
        log.info("Created patient profile for doctor user ${user.id}")
        val principal = user.email ?: user.phone!!
        val tokenResult = jwt.generate(user.id, principal, Role.PATIENT.name)
        userSessions.save(
            UserSession(
                user = user,
                tokenJti = tokenResult.jti,
                expiresAt = OffsetDateTime.ofInstant(tokenResult.expiresAt, ZoneOffset.UTC)
            )
        )
        return TokenResponse(token = tokenResult.token, forcePasswordReset = user.forcePasswordReset)
    }

    // ---------- Verify Firebase ID Token (Doctor Phone OTP) ----------
    /**
     * Doctor app sends Firebase ID token after phone OTP sign-in.
     * Backend verifies token, resolves user by phone, checks DOCTOR role and enabled; returns JWT.
     */
    @PostMapping("/verify")
    fun verifyFirebaseToken(@RequestHeader("Authorization") authorization: String?): TokenResponse {
        val bearer = authorization?.takeIf { it.startsWith("Bearer ") }?.substring(7)?.trim()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing Bearer token")
        if (!firebaseAuthService.isConfigured()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Firebase verification not configured")
        }
        val decodedToken = firebaseAuthService.verifyIdToken(bearer)
            .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired token") }
        val uid = decodedToken.uid
        val phone = firebaseAuthService.getPhoneNumberByUid(uid)
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access restricted to doctors.")
        val user = users.findByPhone(phone).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access restricted to doctors.")
        if (user.role != Role.DOCTOR) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access restricted to doctors.")
        }
        if (!user.enabled) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Your account has been blocked.")
        }
        val principal = user.email ?: user.phone!!
        val tokenResult = jwt.generate(user.id, principal, Role.DOCTOR.name)
        userSessions.save(
            UserSession(
                user = user,
                tokenJti = tokenResult.jti,
                expiresAt = OffsetDateTime.ofInstant(tokenResult.expiresAt, ZoneOffset.UTC)
            )
        )
        userManagementService.updateLastLogin(user.id)
        return TokenResponse(token = tokenResult.token, forcePasswordReset = user.forcePasswordReset)
    }

    // ---------- Register ----------
    data class TokenResponse(
        val token: String,
        val forcePasswordReset: Boolean = false // ✅ NEW
    )

    /**
     * Creates a DOCTOR user (or adds DOCTOR role to an existing user, e.g. patient creating doctor account).
     * Saves DoctorProfile, consumes the invitation key, and returns JWT.
     */
    @PostMapping("/register")
    fun register(@RequestBody @Valid r: RegisterRequest): TokenResponse {
        // SECURITY (NEW): Enforce strong password before hashing; never log password.
        PasswordPolicy.validate(r.password)?.let { msg ->
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, msg)
        }
        val inv = invites.findByKeyCode(r.key.trim())
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid key")
        if (inv.consumed) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Key already used")
        }
        if (inv.isExpired()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Key expired")
        }
        if (inv.purpose == InvitationKey.PURPOSE_CLINIC_RECEPTIONIST_INVITE) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "This invitation is for clinic staff signup, not doctor registration"
            )
        }

        val phoneRaw = r.phone?.trim()?.takeIf { it.isNotBlank() }
        val phoneNorm = phoneRaw?.let { PhoneNormalizer.normalize(it) }
        if (phoneRaw != null && phoneNorm == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid phone number")
        }
        val emailTrimmed = r.email.trim().lowercase()

        inv.emailSentTo?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { intended ->
            if (emailTrimmed != intended) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Use the email address your invitation was sent to, or request a new invitation."
                )
            }
        }

        val byPhoneRaw = phoneRaw?.let { users.findByPhone(it).orElse(null) }
        val byPhoneNorm = phoneNorm?.let { users.findByPhone(it).orElse(null) }
        val byPhone = byPhoneRaw ?: byPhoneNorm
        val byEmail = users.findByEmailIgnoreCase(emailTrimmed).orElse(null)

        if (byPhone != null && byEmail != null && byPhone.id != byEmail.id) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "The phone number and email belong to different accounts. Use the matching phone and email or contact support."
            )
        }

        val existingUser = when {
            byPhone != null -> {
                val phoneUserEmail = byPhone.email
                if (!phoneUserEmail.isNullOrBlank() && !phoneUserEmail.trim().equals(emailTrimmed, ignoreCase = true)) {
                    throw ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "This phone number is already registered with a different email address."
                    )
                }
                byPhone
            }
            byEmail != null -> byEmail
            else -> null
        }

        if (existingUser == null) {
            val emailHash = sha256Hex(emailTrimmed)
            if (users.findByEmailOriginalHashAndAccountStatus(emailHash, User.AccountStatus.DELETED).isPresent) {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This email was previously used for a Shifa account. Use a different email or contact support."
                )
            }
        }

        val user = if (existingUser != null) {
            if (phoneNorm != null && !existingUser.phone.isNullOrBlank()) {
                val ep = existingUser.phone!!.trim()
                if (ep != phoneNorm && (phoneRaw == null || ep != phoneRaw.trim())) {
                    throw ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "This email is already registered with a different phone number."
                    )
                }
            }
            // Existing account (e.g. patient): add DOCTOR role and doctor profile; copy patient avatar if present
            if (!existingUser.enabled) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "Account is disabled.")
            }
            if (!userRoles.existsByUserIdAndRole(existingUser.id, Role.DOCTOR)) {
                userRoles.save(
                    com.shifa.auth.domain.UserRole(user = existingUser, role = Role.DOCTOR)
                )
            }
            val patientProfile = patients.findByUserId(existingUser.id).orElse(null)
            val patientPhotoUrl = patientProfile?.photoUrl?.takeIf { it.isNotBlank() }
            if (doctors.findByUserId(existingUser.id).isEmpty) {
                val doc = DoctorProfile(
                    user = existingUser,
                    firstName = r.firstName.trim(),
                    lastName = r.lastName.trim(),
                    avatarUrl = patientPhotoUrl
                )
                doc.timeZone = r.timeZone?.takeIf { it.isNotBlank() } ?: doc.timeZone
                doctors.save(doc)
            } else {
                // Already has doctor profile: ensure avatar is set from patient if doctor has none
                val existingDoctor = doctors.findByUserId(existingUser.id).orElse(null)
                if (existingDoctor != null && (existingDoctor.avatarUrl.isNullOrBlank()) && patientPhotoUrl != null) {
                    existingDoctor.avatarUrl = patientPhotoUrl
                    doctors.save(existingDoctor)
                }
            }
            existingUser.role = Role.DOCTOR
            existingUser.passwordHash = encoder.encode(r.password)!!
            if (existingUser.email.isNullOrBlank()) {
                existingUser.email = emailTrimmed
            }
            if (existingUser.phone.isNullOrBlank() && phoneNorm != null) {
                existingUser.phone = phoneNorm
            }
            users.save(existingUser)
            log.info("Added DOCTOR role and profile to existing user ${existingUser.id}")
            existingUser
        } else {
            if (phoneNorm != null) {
                if (users.findByPhone(phoneNorm).isPresent || phoneRaw?.let { users.findByPhone(it).isPresent } == true) {
                    throw ResponseStatusException(HttpStatus.CONFLICT, "Phone already registered")
                }
            }
            if (users.findByEmailIgnoreCase(emailTrimmed).isPresent) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Email already registered")
            }
            // New user: create user, DOCTOR role, and doctor profile
            users.save(
                User(
                    email = emailTrimmed,
                    phone = phoneNorm,
                    passwordHash = encoder.encode(r.password)!!,
                    role = Role.DOCTOR
                )
            ).also { newUser ->
                userRoles.save(
                    com.shifa.auth.domain.UserRole(user = newUser, role = Role.DOCTOR)
                )
                val doc = DoctorProfile(
                    user = newUser,
                    firstName = r.firstName.trim(),
                    lastName = r.lastName.trim()
                )
                doc.timeZone = r.timeZone?.takeIf { it.isNotBlank() } ?: doc.timeZone
                doctors.save(doc)
            }
        }

        // Consume the invitation key
        inv.consumed = true
        inv.consumedAt = OffsetDateTime.now()
        inv.consumedByUserId = user.id
        invites.save(inv)

        val principal = user.email?.takeIf { it.isNotBlank() } ?: user.phone
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Account must have email or phone")
        val tokenResult = jwt.generate(user.id, principal, Role.DOCTOR.name)
        userSessions.save(
            UserSession(
                user = user,
                tokenJti = tokenResult.jti,
                expiresAt = OffsetDateTime.ofInstant(tokenResult.expiresAt, ZoneOffset.UTC)
            )
        )
        return TokenResponse(
            token = tokenResult.token,
            forcePasswordReset = user.forcePasswordReset
        )
    }

    @PostMapping("/register-clinic-staff")
    fun registerClinicStaff(@RequestBody @Valid r: RegisterClinicStaffRequest): TokenResponse {
        PasswordPolicy.validate(r.password)?.let { msg ->
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, msg)
        }
        val inv = invites.findByKeyCode(r.key.trim())
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid key")
        if (inv.consumed) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Key already used")
        }
        if (inv.isExpired()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Key expired")
        }
        if (inv.purpose != InvitationKey.PURPOSE_CLINIC_RECEPTIONIST_INVITE ||
            inv.clinic == null || inv.membershipRole == null
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid key for clinic staff registration")
        }
        val clinic = clinics.findById(inv.clinic!!.id).orElseThrow {
            ResponseStatusException(HttpStatus.BAD_REQUEST, "Clinic no longer exists")
        }
        val membershipRole =
            inv.membershipRole ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid invitation")

        val phoneRaw = r.phone?.trim()?.takeIf { it.isNotBlank() }
        val phoneNorm = phoneRaw?.let { PhoneNormalizer.normalize(it) }
        if (phoneRaw != null && phoneNorm == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid phone number")
        }
        val emailTrimmed = r.email.trim().lowercase()

        inv.emailSentTo?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { intended ->
            if (emailTrimmed != intended) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Use the email address your invitation was sent to, or request a new invitation."
                )
            }
        }

        val byPhoneRaw = phoneRaw?.let { users.findByPhone(it).orElse(null) }
        val byPhoneNorm = phoneNorm?.let { users.findByPhone(it).orElse(null) }
        val byPhone = byPhoneRaw ?: byPhoneNorm
        val byEmail = users.findByEmailIgnoreCase(emailTrimmed).orElse(null)

        if (byPhone != null && byEmail != null && byPhone.id != byEmail.id) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "The phone number and email belong to different accounts. Use the matching phone and email or contact support."
            )
        }

        val existingUser = when {
            byPhone != null -> {
                val phoneUserEmail = byPhone.email
                if (!phoneUserEmail.isNullOrBlank() && !phoneUserEmail.trim().equals(emailTrimmed, ignoreCase = true)) {
                    throw ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "This phone number is already registered with a different email address."
                    )
                }
                byPhone
            }
            byEmail != null -> byEmail
            else -> null
        }

        if (existingUser == null) {
            val emailHash = sha256Hex(emailTrimmed)
            if (users.findByEmailOriginalHashAndAccountStatus(emailHash, User.AccountStatus.DELETED).isPresent) {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This email was previously used for a Shifa account. Use a different email or contact support."
                )
            }
        }

        val user = if (existingUser != null) {
            if (phoneNorm != null && !existingUser.phone.isNullOrBlank()) {
                val ep = existingUser.phone!!.trim()
                if (ep != phoneNorm && (phoneRaw == null || ep != phoneRaw.trim())) {
                    throw ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "This email is already registered with a different phone number."
                    )
                }
            }
            if (!existingUser.enabled) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "Account is disabled.")
            }
            if (!userRoles.existsByUserIdAndRole(existingUser.id, Role.CLINIC_STAFF)) {
                userRoles.save(com.shifa.auth.domain.UserRole(user = existingUser, role = Role.CLINIC_STAFF))
            }
            existingUser.passwordHash = encoder.encode(r.password)!!
            if (existingUser.email.isNullOrBlank()) {
                existingUser.email = emailTrimmed
            }
            if (existingUser.phone.isNullOrBlank() && phoneNorm != null) {
                existingUser.phone = phoneNorm
            }
            users.save(existingUser)
            log.info("Ensured CLINIC_STAFF role on existing user ${existingUser.id} for clinic signup")
            existingUser
        } else {
            if (phoneNorm != null) {
                if (users.findByPhone(phoneNorm).isPresent || phoneRaw?.let { users.findByPhone(it).isPresent } == true) {
                    throw ResponseStatusException(HttpStatus.CONFLICT, "Phone already registered")
                }
            }
            if (users.findByEmailIgnoreCase(emailTrimmed).isPresent) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Email already registered")
            }
            users.save(
                User(
                    email = emailTrimmed,
                    phone = phoneNorm,
                    passwordHash = encoder.encode(r.password)!!,
                    role = Role.CLINIC_STAFF,
                ),
            ).also { newUser ->
                userRoles.save(com.shifa.auth.domain.UserRole(user = newUser, role = Role.CLINIC_STAFF))
            }
        }

        user.staffFirstName = r.firstName.trim()
        user.staffLastName = r.lastName.trim()
        val tzEffective = r.timeZone?.takeIf { it.isNotBlank() }
            ?: user.staffTimeZone?.takeIf { it.isNotBlank() }
            ?: "UTC"
        user.staffTimeZone = tzEffective
        val patientProfile = patients.findByUserId(user.id).orElse(null)
        val patientPhotoUrl = patientProfile?.photoUrl?.takeIf { it.isNotBlank() }
        if (user.staffPhotoUrl.isNullOrBlank() && patientPhotoUrl != null) {
            user.staffPhotoUrl = patientPhotoUrl
        }
        users.save(user)

        val existingMembership = clinicMemberships.findByClinic_IdAndUser_Id(clinic.id, user.id)
        if (existingMembership != null) {
            existingMembership.membershipRole = membershipRole
            existingMembership.active = true
            existingMembership.doctorProfile = null
            clinicMemberships.save(existingMembership)
        } else {
            clinicMemberships.save(
                com.shifa.clinics.domain.ClinicMembership(
                    clinic = clinic,
                    user = user,
                    membershipRole = membershipRole,
                ),
            )
        }

        inv.consumed = true
        inv.consumedAt = OffsetDateTime.now()
        inv.consumedByUserId = user.id
        invites.save(inv)

        val principal = user.email?.takeIf { it.isNotBlank() } ?: user.phone
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Account must have email or phone")
        val tokenResult = jwt.generate(user.id, principal, Role.CLINIC_STAFF.name)
        userSessions.save(
            UserSession(
                user = user,
                tokenJti = tokenResult.jti,
                expiresAt = OffsetDateTime.ofInstant(tokenResult.expiresAt, ZoneOffset.UTC),
            ),
        )
        return TokenResponse(token = tokenResult.token, forcePasswordReset = user.forcePasswordReset)
    }

    // ---------- Login ----------

    /**
     * Accepts email OR phone as 'username'. Returns JWT on success.
     *
     * Admin panel: do not use ?app=admin — use POST /api/auth/admin/request-login-otp then /api/auth/admin/verify-login-otp (email 2FA).
     *
     * App-based role enforcement:
     * - Query param ?app=doctor or header X-App: doctor → requires DOCTOR role
     * - Query param ?app=patient or header X-App: patient → requires PATIENT role (auto-adds if missing for existing users)
     * - If app param/header not provided → uses user's primary role (backward compatibility)
     */
	
	@PostMapping("/login", produces = ["application/json"])
	fun login(
        @RequestBody @Valid r: LoginRequest,
        @RequestParam(required = false) app: String?,
        request: jakarta.servlet.http.HttpServletRequest
    ): TokenResponse {
		// Validate request
		if (r.username.isBlank()) {
			throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Username is required")
		}
		if (r.password.isBlank()) {
			throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Password is required")
		}

		val appTypeEarly = app?.lowercase() ?: request.getHeader("X-App")?.lowercase()
		if (appTypeEarly == "admin") {
			throw ResponseStatusException(
				HttpStatus.BAD_REQUEST,
				"Admin sign-in requires email verification. Use POST /api/auth/admin/request-login-otp and POST /api/auth/admin/verify-login-otp."
			)
		}

		val user = resolveUserForLogin(r.username.trim(), request)

		// Check if account is disabled
		if (!user.enabled) {
			userActivityService.logActivity(
				user = user,
				activityType = "LOGIN",
				success = false,
				failureReason = "Account disabled",
				request = request
			)
			throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is disabled")
		}

		// Check if account is locked
		if (user.isLocked()) {
			userActivityService.logActivity(
				user = user,
				activityType = "LOGIN",
				success = false,
				failureReason = "Account locked",
				request = request
			)
			throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is locked")
		}

		val passwordMatches = encoder.matches(r.password, user.passwordHash)
		
		if (!passwordMatches) {
			userActivityService.logActivity(
				user = user,
				activityType = "LOGIN",
				success = false,
				failureReason = "Invalid password",
				request = request
			)
			userManagementService.incrementFailedLoginAttempts(user.id)
			throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials - password mismatch")
		}

		// Log successful login
		userActivityService.logActivity(
			user = user,
			activityType = "LOGIN",
			success = true,
			request = request
		)
		
		// Update last login
		userManagementService.updateLastLogin(user.id)

        // App-based role enforcement (multi-role support)
        val appType = app?.lowercase() ?: request.getHeader("X-App")?.lowercase()

        when (appType) {
            "doctor" -> {
                val okDoctorApp = userHasAppRole(user, Role.DOCTOR) || userHasAppRole(user, Role.CLINIC_STAFF)
                if (!okDoctorApp) {
                    userActivityService.logActivity(
                        user = user,
                        activityType = "LOGIN",
                        success = false,
                        failureReason = "Missing doctor app role",
                        request = request
                    )
                    throw ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Access denied: Doctor app requires DOCTOR or CLINIC_STAFF role"
                    )
                }
            }
            "patient" -> {
                if (!userHasAppRole(user, Role.PATIENT)) {
                    userActivityService.logActivity(
                        user = user,
                        activityType = "LOGIN",
                        success = false,
                        failureReason = "Patient account required",
                        request = request
                    )
                    throw ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "You need to create a patient account first. Use Create Account in the patient app and link your doctor account."
                    )
                }
            }
        }

        val tokenRole = when (appType) {
            "doctor" -> if (userHasAppRole(user, Role.DOCTOR)) Role.DOCTOR else Role.CLINIC_STAFF
            "patient" -> Role.PATIENT
            else -> user.role
        }
		val principal = user.username ?: user.email ?: user.phone!!
		val tokenResult = jwt.generate(user.id, principal, tokenRole.name)
		userSessions.save(
			UserSession(
				user = user,
				tokenJti = tokenResult.jti,
				expiresAt = OffsetDateTime.ofInstant(tokenResult.expiresAt, ZoneOffset.UTC)
			)
		)
		return TokenResponse(
			token = tokenResult.token,
			forcePasswordReset = user.forcePasswordReset
		)
	}

    // ---------- Admin login: password + email OTP (2FA) ----------

    /**
     * Step 1: validate admin credentials and send a 6-digit code to the admin's email.
     * Does not return a JWT.
     */
    @PostMapping("/admin/request-login-otp", produces = ["application/json"])
    fun adminRequestLoginOtp(
        @RequestBody @Valid r: LoginRequest,
        request: jakarta.servlet.http.HttpServletRequest
    ): AdminLoginOtpSentResponse {
        val user = resolveUserForLogin(r.username.trim(), request)
        if (!user.enabled) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is disabled")
        }
        if (user.isLocked()) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is locked")
        }
        if (!encoder.matches(r.password, user.passwordHash)) {
            userManagementService.incrementFailedLoginAttempts(user.id)
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
        }
        if (!userHasAppRole(user, Role.ADMIN)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: This app requires ADMIN role")
        }
        val email = user.email?.trim()?.lowercase()
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Admin account must have an email address for two-factor sign-in"
            )
        if (!emailOtpService.sendCode(email, EmailVerificationCode.PURPOSE_ADMIN_LOGIN)) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many verification requests. Please try again later.")
        }
        return AdminLoginOtpSentResponse(sent = true, emailHint = maskEmailHint(email))
    }

    /**
     * Step 2: re-check password, verify email OTP, issue ADMIN JWT.
     */
    @PostMapping("/admin/verify-login-otp", produces = ["application/json"])
    fun adminVerifyLoginOtp(
        @RequestBody @Valid r: AdminVerifyLoginOtpRequest,
        request: jakarta.servlet.http.HttpServletRequest
    ): TokenResponse {
        val user = resolveUserForLogin(r.username.trim(), request)
        if (!user.enabled) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is disabled")
        }
        if (user.isLocked()) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is locked")
        }
        if (!encoder.matches(r.password, user.passwordHash)) {
            userManagementService.incrementFailedLoginAttempts(user.id)
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
        }
        if (!userHasAppRole(user, Role.ADMIN)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: This app requires ADMIN role")
        }
        val email = user.email?.trim()?.lowercase()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Admin account must have an email address")
        if (!emailOtpService.verify(email, r.code.trim(), EmailVerificationCode.PURPOSE_ADMIN_LOGIN)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired verification code")
        }
        userActivityService.logActivity(user = user, activityType = "LOGIN", success = true, request = request)
        userManagementService.updateLastLogin(user.id)
        val principal = user.username ?: user.email ?: user.phone!!
        val tokenResult = jwt.generate(user.id, principal, Role.ADMIN.name)
        userSessions.save(
            UserSession(
                user = user,
                tokenJti = tokenResult.jti,
                expiresAt = OffsetDateTime.ofInstant(tokenResult.expiresAt, ZoneOffset.UTC)
            )
        )
        return TokenResponse(token = tokenResult.token, forcePasswordReset = user.forcePasswordReset)
    }

    // ---------- Register Patient ----------

    /**
     * Creates a PATIENT user, saves PatientProfile, and returns JWT.
     * Verification: email + emailOtp, or Uzbek phone + smsOtp (no email).
     */
    @PostMapping("/register-patient")
    fun registerPatient(@RequestBody @Valid r: RegisterPatientRequest): TokenResponse {
        val emailTrimmed = r.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (emailTrimmed != null && !emailTrimmed.contains('@')) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid email address")
        }
        val phoneRaw = r.phone?.trim()?.takeIf { it.isNotBlank() }
        val phoneNormalized = phoneRaw?.let { PhoneNormalizer.normalize(it) }
        if (phoneRaw != null && phoneNormalized == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid phone number")
        }
        if (emailTrimmed == null && phoneNormalized == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Email or phone number is required")
        }

        when {
            emailTrimmed != null -> {
                if (r.emailOtp.isNullOrBlank()) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Email verification code required")
                }
                if (!emailOtpService.verify(emailTrimmed, r.emailOtp!!)) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired email verification code")
                }
            }
            phoneNormalized != null && PhoneNormalizer.isUzbekMobile(phoneNormalized) -> {
                if (r.smsOtp.isNullOrBlank()) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "SMS verification code required")
                }
                if (!smsOtpService.verify(phoneNormalized, r.smsOtp!!)) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired SMS verification code")
                }
            }
            else -> {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Email is required for non-Uzbek phone numbers"
                )
            }
        }

        PasswordPolicy.validate(r.password)?.let { msg ->
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, msg)
        }
        emailTrimmed?.let { email ->
            if (users.findByEmail(email).isPresent) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Email already registered")
            }
        }
        phoneNormalized?.let { pn ->
            if (users.findByPhone(pn).isPresent || phoneRaw?.let { users.findByPhone(it).isPresent } == true) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Phone already registered")
            }
            if (patients.findByPhoneNormalized(pn).isPresent) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Patient with this phone number already exists.")
            }
        }

        val user = users.save(
            User(
                email = emailTrimmed,
                phone = phoneNormalized,
                passwordHash = encoder.encode(r.password)!!,
                role = Role.PATIENT,
                emailVerified = emailTrimmed != null,
            )
        )

        userRoles.save(
            com.shifa.auth.domain.UserRole(
                user = user,
                role = Role.PATIENT
            )
        )

        val patientProfile = com.shifa.patients.domain.PatientProfile(
            fullName = "${r.firstName.trim()} ${r.lastName.trim()}".trim(),
            phone = phoneNormalized ?: phoneRaw,
            phoneNormalized = phoneNormalized,
            email = emailTrimmed,
            address = r.address?.trim(),
            birthDate = r.birthDate?.let { java.time.LocalDate.parse(it) },
            gender = r.gender?.trim()?.takeIf { it.isNotEmpty() },
            language = r.language?.trim(),
            documents = mutableListOf()
        )
        patientProfile.user = user
        patients.save(patientProfile)

        val principal = emailTrimmed ?: phoneNormalized!!
        val tokenResult = jwt.generate(user.id, principal, user.role.name)
        userSessions.save(
            UserSession(
                user = user,
                tokenJti = tokenResult.jti,
                expiresAt = OffsetDateTime.ofInstant(tokenResult.expiresAt, ZoneOffset.UTC)
            )
        )
        return TokenResponse(
            token = tokenResult.token,
            forcePasswordReset = user.forcePasswordReset
        )
    }

    // ---------- Change/Reset Password ----------
    // For normal change: requires currentPassword + newPassword.
    // For forced reset (forcePasswordReset=true): only newPassword needed.

    // ---------- Forgot Password ----------

    /**
     * Reset password using either:
     * 1. Firebase ID token (legacy phone OTP flow) — idToken field
     * 2. Email OTP (new flow) — email + emailOtp fields
     * Returns JWT for auto-login after password reset.
     */
    @PostMapping("/forgot-password-reset")
    fun forgotPasswordReset(
        @RequestBody @Valid r: ForgotPasswordResetRequest,
        @RequestParam(required = false) app: String?,
        request: jakarta.servlet.http.HttpServletRequest
    ): TokenResponse {
        PasswordPolicy.validate(r.newPassword)?.let { msg ->
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, msg)
        }

        val user = if (!r.email.isNullOrBlank() && !r.emailOtp.isNullOrBlank()) {
            // send-forgot-password-otp returns channel="email" whenever the account has an
            // email, even when the caller supplied a phone number. Resolve the account from
            // either form, then verify the code against the address it was actually sent to.
            val identifier = r.email.trim()
            val resolved = users.findByEmailIgnoreCase(identifier).orElse(null)
                ?: users.findByPhone(identifier).orElse(null)
                ?: PhoneNormalizer.normalize(identifier)?.let { users.findByPhone(it).orElse(null) }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
            val otpEmail = resolved.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "No email address on file. Please contact support."
                )
            if (!emailOtpService.verify(otpEmail, r.emailOtp.trim(),
                    com.shifa.auth.domain.EmailVerificationCode.PURPOSE_FORGOT_PASSWORD)) {
                throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired verification code")
            }
            resolved
        } else if (!r.phone.isNullOrBlank() && !r.smsOtp.isNullOrBlank()) {
            val phoneNorm = PhoneNormalizer.normalize(r.phone.trim())
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid phone number")
            if (!smsOtpService.verify(
                    phoneNorm,
                    r.smsOtp.trim(),
                    com.shifa.auth.domain.SmsVerificationCode.PURPOSE_FORGOT_PASSWORD
                )) {
                throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired verification code")
            }
            users.findByPhone(phoneNorm).orElse(null)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        } else if (!r.idToken.isNullOrBlank()) {
            // Legacy flow: Firebase phone OTP
            if (!firebaseAuthService.isConfigured()) {
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Firebase verification not configured")
            }
            val decodedToken = firebaseAuthService.verifyIdToken(r.idToken)
                .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired token") }
            val phone = firebaseAuthService.getPhoneNumberByUid(decodedToken.uid)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone number not found")
            users.findByPhone(phone).orElse(null)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        } else {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Provide email+emailOtp, phone+smsOtp, or idToken"
            )
        }

        if (!user.enabled) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Account is disabled")
        }
        val appType = r.app?.trim()?.lowercase()
            ?: app?.trim()?.lowercase()
            ?: request.getHeader("X-App")?.trim()?.lowercase()
        // Choose token role by app context first (prevents admin app from receiving doctor token),
        // then fall back to backward-compatible priority.
        val tokenRole = when (appType) {
            "admin" -> {
                when {
                    userRoles.existsByUserIdAndRole(user.id, Role.ADMIN) -> Role.ADMIN
                    user.role == Role.ADMIN -> Role.ADMIN
                    else -> throw ResponseStatusException(HttpStatus.FORBIDDEN, "No admin account found")
                }
            }
            "doctor" -> {
                when {
                    userRoles.existsByUserIdAndRole(user.id, Role.DOCTOR) -> Role.DOCTOR
                    user.role == Role.DOCTOR -> Role.DOCTOR
                    else -> throw ResponseStatusException(HttpStatus.FORBIDDEN, "No doctor account found")
                }
            }
            "patient" -> {
                when {
                    userRoles.existsByUserIdAndRole(user.id, Role.PATIENT) -> Role.PATIENT
                    user.role == Role.PATIENT -> Role.PATIENT
                    else -> throw ResponseStatusException(HttpStatus.FORBIDDEN, "No patient account found")
                }
            }
            else -> when {
                // Backward compatibility for callers that do not send app.
                userRoles.existsByUserIdAndRole(user.id, Role.DOCTOR) -> Role.DOCTOR
                userRoles.existsByUserIdAndRole(user.id, Role.PATIENT) -> Role.PATIENT
                userRoles.existsByUserIdAndRole(user.id, Role.ADMIN) -> Role.ADMIN
                user.role == Role.DOCTOR -> Role.DOCTOR
                user.role == Role.PATIENT -> Role.PATIENT
                user.role == Role.ADMIN -> Role.ADMIN
                else -> throw ResponseStatusException(HttpStatus.FORBIDDEN, "No doctor, patient, or admin account found")
            }
        }
        user.passwordHash = encoder.encode(r.newPassword)!!
        user.forcePasswordReset = false
        // Clear lockout so the next login can use the new password immediately
        // (reset already proves ownership via OTP / Firebase token).
        user.lockedUntil = null
        user.failedLoginAttempts = 0
        users.save(user)
        val principal = user.email ?: user.phone!!
        val tokenResult = jwt.generate(user.id, principal, tokenRole.name)
        userSessions.save(
            UserSession(
                user = user,
                tokenJti = tokenResult.jti,
                expiresAt = OffsetDateTime.ofInstant(tokenResult.expiresAt, ZoneOffset.UTC)
            )
        )
        return TokenResponse(token = tokenResult.token, forcePasswordReset = false)
    }

    @PostMapping("/reset-password")
    fun resetPassword(
        @RequestBody @Valid r: ChangePasswordRequest,
        @AuthenticationPrincipal principal: UserDetails
    ): Map<String, String> {
        // SECURITY (NEW): Enforce strong password for new password; never log password.
        PasswordPolicy.validate(r.newPassword)?.let { msg ->
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, msg)
        }
        val userId = principal.username.toLong()
        val user = users.findById(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        if (!user.forcePasswordReset) {
            if (r.currentPassword.isNullOrBlank()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is required")
            }
            if (!encoder.matches(r.currentPassword, user.passwordHash)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect")
            }
        }

        user.passwordHash = encoder.encode(r.newPassword)!!
        user.forcePasswordReset = false
        users.save(user)

        return mapOf("message" to "Password updated successfully")
    }

    private fun resolveUserForLogin(username: String, request: jakarta.servlet.http.HttpServletRequest): User {
        return try {
            users.findByUsername(username).orElseGet {
                users.findByEmailIgnoreCase(username).orElseGet {
                    users.findByPhone(username).orElseGet {
                        PhoneNormalizer.normalize(username)?.let { normalized ->
                            users.findByPhone(normalized).orElse(null)
                        } ?: throw run {
                            val clientIp = request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
                                ?: request.remoteAddr
                            log.warn("Failed login attempt (unknown identifier) from ip={} path={}", clientIp, request.servletPath)
                            ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
                        }
                    }
                }
            }
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            log.error("Login unexpected exception: ${e.message}", e)
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Login error: ${e.message}")
        }
    }

    private fun userHasAppRole(user: User, requiredRole: Role): Boolean {
        val roleRows = userRoles.findByUserId(user.id)
        return if (roleRows.isEmpty()) {
            user.role == requiredRole
        } else {
            roleRows.any { it.role == requiredRole }
        }
    }

    private fun ensureForgotPasswordAccountForApp(user: User, appType: String?) {
        when (appType) {
            "doctor" -> {
                val hasDoctorRole = userHasAppRole(user, Role.DOCTOR)
                val hasStaff = userHasAppRole(user, Role.CLINIC_STAFF)
                if (!hasDoctorRole && !hasStaff) {
                    throw ResponseStatusException(HttpStatus.FORBIDDEN, "No doctor app access")
                }
                if (hasDoctorRole && doctors.findByUserId(user.id).isEmpty) {
                    throw ResponseStatusException(HttpStatus.FORBIDDEN, "No doctor account found")
                }
                if (!hasDoctorRole && hasStaff && clinicMemberships.findByUserIdAndActiveTrue(user.id).isEmpty()) {
                    throw ResponseStatusException(HttpStatus.FORBIDDEN, "No clinic membership found for staff account")
                }
            }
            "patient" -> {
                val hasPatientRole = userHasAppRole(user, Role.PATIENT)
                if (!hasPatientRole || patients.findByUserId(user.id).isEmpty) {
                    throw ResponseStatusException(HttpStatus.FORBIDDEN, "No patient account found")
                }
            }
            "admin" -> {
                val hasAdminRole = userHasAppRole(user, Role.ADMIN)
                if (!hasAdminRole) {
                    throw ResponseStatusException(HttpStatus.FORBIDDEN, "No admin account found")
                }
            }
        }
    }

    private fun maskEmailHint(email: String): String {
        val at = email.indexOf('@')
        if (at <= 0 || at >= email.length - 1) return "***"
        val local = email.substring(0, at)
        val domain = email.substring(at + 1)
        val first = local.firstOrNull() ?: '*'
        return "$first***@$domain"
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun smsPurposeToEmailPurpose(smsPurpose: String): String = when (smsPurpose) {
        com.shifa.auth.domain.SmsVerificationCode.PURPOSE_FORGOT_PASSWORD ->
            com.shifa.auth.domain.EmailVerificationCode.PURPOSE_FORGOT_PASSWORD
        com.shifa.auth.domain.SmsVerificationCode.PURPOSE_ACCOUNT_DELETION ->
            com.shifa.auth.domain.EmailVerificationCode.PURPOSE_ACCOUNT_DELETION
        else -> com.shifa.auth.domain.EmailVerificationCode.PURPOSE_REGISTRATION
    }

    private fun sendEmailOtpChannelOrThrow(
        email: String,
        purpose: String,
        smsFallback: Boolean = false,
    ): SendOtpChannelResponse {
        if (!emailOtpService.sendCode(email, purpose)) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, MSG_OTP_RATE_LIMIT)
        }
        return SendOtpChannelResponse(channel = "email", smsFallbackUsed = smsFallback)
    }

    private fun shouldFallbackSmsToEmail(reason: com.shifa.auth.application.SmsOtpSendFailure): Boolean =
        reason == com.shifa.auth.application.SmsOtpSendFailure.SMS_PROVIDER_FAILED ||
            reason == com.shifa.auth.application.SmsOtpSendFailure.SMS_SEND_LIMIT_REACHED ||
            reason == com.shifa.auth.application.SmsOtpSendFailure.RATE_LIMITED

    private fun throwSmsOtpFailure(result: com.shifa.auth.application.SmsOtpSendResult.Failure): Nothing {
        when (result.reason) {
            // Cap reached with no fallback email: force the client onto the email path.
            com.shifa.auth.application.SmsOtpSendFailure.SMS_SEND_LIMIT_REACHED,
            com.shifa.auth.application.SmsOtpSendFailure.RATE_LIMITED ->
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, MSG_SMS_LIMIT_USE_EMAIL)
            com.shifa.auth.application.SmsOtpSendFailure.INVALID_PHONE ->
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid phone number")
            com.shifa.auth.application.SmsOtpSendFailure.SMS_PROVIDER_FAILED ->
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, MSG_SMS_UNAVAILABLE)
        }
    }

    private companion object {
        const val MSG_OTP_RATE_LIMIT = "Too many verification requests. Please try again later."
        const val MSG_SMS_UNAVAILABLE =
            "SMS could not be sent. Please use email verification or contact support."
        const val MSG_SMS_LIMIT_USE_EMAIL =
            "SMS could not be sent. Please use email verification or contact support."
    }
}
