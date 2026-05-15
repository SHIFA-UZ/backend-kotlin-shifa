package com.shifa.web

import com.shifa.domain.*
import com.shifa.repo.*
import com.shifa.security.JwtService
import com.shifa.util.PhoneNormalizer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.ZoneOffset
import com.shifa.security.PasswordPolicy
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*
import java.time.OffsetDateTime
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.slf4j.LoggerFactory

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val users: UserRepository,
    private val invites: InvitationKeyRepository,
    private val doctors: DoctorProfileRepository,
    private val patients: com.shifa.repo.PatientProfileRepository,
    private val userSessions: UserSessionRepository,
    private val encoder: PasswordEncoder,
    private val jwt: JwtService,
    private val userActivityService: com.shifa.service.UserActivityService,
    private val userManagementService: com.shifa.service.UserManagementService,
    private val userRoles: com.shifa.repo.UserRoleRepository,
    private val firebaseAuthService: com.shifa.service.FirebaseAuthService,
    private val emailOtpService: com.shifa.service.EmailOtpService
) {
    private val log = LoggerFactory.getLogger(AuthController::class.java)
    // ---------- VerifyKey ----------
    data class VerifyKeyRequest(@field:NotBlank val key: String)
    data class VerifyKeyResponse(val valid: Boolean)

    /**
     * VerifyKeyScreen calls this to check if the one-time key is valid & not consumed.
     * UI: show "Next" if valid, else show error.
     */
    @PostMapping("/verify-key")
    fun verifyKey(@RequestBody @Valid req: VerifyKeyRequest): VerifyKeyResponse {
        val key = invites.findByKeyCode(req.key.trim())
        return VerifyKeyResponse(key != null && key.isValid())
    }

    // ---------- Check existing patient (for doctor registration UX) ----------
    data class CheckExistingPatientRequest(
        @field:NotBlank val firstName: String,
        @field:NotBlank val lastName: String,
        /** Optional; when set, used together with email to detect an existing patient account. */
        val phone: String? = null,
        @field:NotBlank @field:Email val email: String
    )
    data class CheckExistingPatientResponse(
        val found: Boolean,
        val fullName: String? = null,
        val photoUrl: String? = null,
        val email: String? = null
    )

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
    data class CheckExistingDoctorRequest(
        val phone: String? = null,
        val email: String? = null
    )
    data class CheckExistingDoctorResponse(
        val firstName: String,
        val lastName: String
    )

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
    data class CheckIdentifierRequest(
        val phone: String? = null,
        val email: String? = null
    )
    data class CheckIdentifierResponse(
        val type: String, // "doctor" | "patient" | "none"
        val firstName: String? = null,
        val lastName: String? = null
    )

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
    data class SendEmailOtpRequest(
        @field:NotBlank @field:Email val email: String,
        val purpose: String? = null
    )
    data class SendEmailOtpResponse(val sent: Boolean = true)

    /**
     * Sends a 6-digit OTP to the given email via Brevo SMTP.
     * Purpose defaults to REGISTRATION for backward compatibility.
     */
    @PostMapping("/send-email-otp")
    fun sendEmailOtp(@RequestBody @Valid req: SendEmailOtpRequest): SendEmailOtpResponse {
        val email = req.email.trim().lowercase()
        val purpose = req.purpose?.uppercase()
            ?: com.shifa.domain.EmailVerificationCode.PURPOSE_REGISTRATION
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

    // ---------- Send login OTP (doctor email OTP sign-in) ----------
    data class SendLoginOtpRequest(@field:NotBlank @field:Email val email: String)

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
        if (!emailOtpService.sendCode(email, com.shifa.domain.EmailVerificationCode.PURPOSE_LOGIN)) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many verification requests. Please try again later.")
        }
        return SendEmailOtpResponse(sent = true)
    }

    // ---------- Verify email OTP → JWT (doctor email OTP sign-in) ----------
    data class VerifyEmailOtpRequest(
        @field:NotBlank @field:Email val email: String,
        @field:NotBlank val code: String
    )

    /**
     * Doctor app: verify the email OTP code and return a JWT.
     * Replaces the Firebase phone OTP verify endpoint for doctor login.
     */
    @PostMapping("/verify-email-otp")
    fun verifyEmailOtp(@RequestBody @Valid req: VerifyEmailOtpRequest): TokenResponse {
        val email = req.email.trim().lowercase()
        if (!emailOtpService.verify(email, req.code.trim(), com.shifa.domain.EmailVerificationCode.PURPOSE_LOGIN)) {
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
    data class SendForgotPasswordOtpRequest(
        @field:NotBlank val identifier: String,
        val app: String? = null
    )

    /**
     * Send a forgot-password OTP. Accepts email or phone as identifier;
     * looks up the user and sends OTP to their email address.
     */
    @PostMapping("/send-forgot-password-otp")
    fun sendForgotPasswordOtp(
        @RequestBody @Valid req: SendForgotPasswordOtpRequest,
        @RequestParam(required = false) app: String?,
        request: jakarta.servlet.http.HttpServletRequest
    ): SendEmailOtpResponse {
        val id = req.identifier.trim()
        val user = users.findByEmail(id).orElse(null)
            ?: users.findByPhone(id).orElse(null)
            ?: PhoneNormalizer.normalize(id)?.let { users.findByPhone(it).orElse(null) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No account found")
        val appType = req.app?.trim()?.lowercase()
            ?: app?.trim()?.lowercase()
            ?: request.getHeader("X-App")?.trim()?.lowercase()
        ensureForgotPasswordAccountForApp(user, appType)
        val email = user.email
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No email address on file. Please contact support.")
        if (!user.enabled) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Account is disabled")
        }
        if (!emailOtpService.sendCode(email.trim().lowercase(), com.shifa.domain.EmailVerificationCode.PURPOSE_FORGOT_PASSWORD)) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please try again later.")
        }
        return SendEmailOtpResponse(sent = true)
    }

    // ---------- Create patient account for existing doctor ----------
    data class CreatePatientForDoctorRequest(
        val phone: String? = null,
        val email: String? = null,
        val emailOtp: String? = null
    )

    /**
     * Patient app: after doctor confirms and verifies email OTP, create PATIENT role + patient profile.
     * They use their doctor password to log in.
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
        val user = phoneTrimmed?.let { users.findByPhone(it).orElse(null) }
            ?: phoneNorm?.let { users.findByPhone(it).orElse(null) }
            ?: emailTrimmed?.let { users.findByEmail(it).orElse(null) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No account found")
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
        userRoles.save(com.shifa.domain.UserRole(user = user, role = Role.PATIENT))
        val doctorProfile = doctors.findByUserId(user.id).orElse(null)
        val fullName = doctorProfile?.let { "${it.firstName} ${it.lastName}".trim() }
            ?: (user.email ?: user.phone ?: "Patient")
        val profilePhoneNorm = user.phone?.let { PhoneNormalizer.normalize(it) }
        val patientProfile = com.shifa.domain.PatientProfile(
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
    data class RegisterRequest(
        @field:NotBlank val firstName: String,
        @field:NotBlank val lastName: String,
        /** Optional. When omitted or blank, the account is created/linked using email only (must still be unique when set). */
        val phone: String? = null,
        @field:NotBlank @field:Email val email: String,
        @field:NotBlank val password: String,
        @field:NotBlank val key: String,
        /** Optional IANA timezone (e.g. Europe/Berlin). If provided, used for new doctor profile so appointment times are correct. */
        val timeZone: String? = null
    )
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
                    com.shifa.domain.UserRole(user = existingUser, role = Role.DOCTOR)
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
            existingUser.passwordHash = encoder.encode(r.password)
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
                    passwordHash = encoder.encode(r.password),
                    role = Role.DOCTOR
                )
            ).also { newUser ->
                userRoles.save(
                    com.shifa.domain.UserRole(user = newUser, role = Role.DOCTOR)
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

    // ---------- Login ----------
    data class LoginRequest(@field:NotBlank val username: String, @field:NotBlank val password: String)

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
        val requiredRole = when (appType) {
            "doctor" -> Role.DOCTOR
            "patient" -> Role.PATIENT
            else -> null // No app specified, use primary role (backward compatibility)
        }

		if (requiredRole != null) {
			val hasRole = userHasAppRole(user, requiredRole)

			if (!hasRole) {
				if (requiredRole == Role.PATIENT) {
					// Doctors must create a patient account first (via create-account flow); do not auto-create
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
				} else {
					// Doctor/Admin app requires the role - reject if missing
					userActivityService.logActivity(
						user = user,
						activityType = "LOGIN",
						success = false,
						failureReason = "Missing required role: ${requiredRole.name}",
						request = request
					)
					throw ResponseStatusException(
						HttpStatus.FORBIDDEN,
						"Access denied: This app requires ${requiredRole.name} role"
					)
				}
			}
		}

		// Determine which role to use for token (use required role if specified, else primary role)
		val tokenRole = requiredRole ?: user.role
		
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
    data class AdminVerifyLoginOtpRequest(
        @field:NotBlank val username: String,
        @field:NotBlank val password: String,
        @field:NotBlank val code: String
    )

    data class AdminLoginOtpSentResponse(val sent: Boolean = true, val emailHint: String? = null)

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
    data class RegisterPatientRequest(
        @field:NotBlank val firstName: String,
        @field:NotBlank val lastName: String,
        val phone: String? = null,
        @field:NotBlank @field:Email val email: String,
        @field:NotBlank val password: String,
        val birthDate: String? = null,
        val gender: String? = null,
        val address: String? = null,
        val language: String? = null,
        val emailOtp: String? = null,
    )

    /**
     * Creates a PATIENT user, saves PatientProfile, and returns JWT.
     * Email and emailOtp are required (sent via send-email-otp).
     * Phone is optional profile data only; SMS / Firebase phone OTP is not used for this flow.
     */
    @PostMapping("/register-patient")
    fun registerPatient(@RequestBody @Valid r: RegisterPatientRequest): TokenResponse {
        val emailTrimmed = r.email.trim().lowercase()
        if (r.emailOtp.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Email verification code required")
        }
        if (!emailOtpService.verify(emailTrimmed, r.emailOtp!!)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired email verification code")
        }
        val phoneRaw = r.phone?.trim()?.takeIf { it.isNotBlank() }
        val phoneNormalized = phoneRaw?.let { PhoneNormalizer.normalize(it) }
        if (phoneRaw != null && phoneNormalized == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid phone number")
        }
        PasswordPolicy.validate(r.password)?.let { msg ->
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, msg)
        }
        if (users.findByEmail(emailTrimmed).isPresent) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Email already registered")
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
                passwordHash = encoder.encode(r.password),
                role = Role.PATIENT
            )
        )

        // Add PATIENT role to user_roles (multi-role support)
        userRoles.save(
            com.shifa.domain.UserRole(
                user = user,
                role = Role.PATIENT
            )
        )

        // Create patient profile and link to user so doctor app shows "Account already available"
        val patientProfile = com.shifa.domain.PatientProfile(
            fullName = "${r.firstName.trim()} ${r.lastName.trim()}".trim(),
            phone = phoneNormalized ?: phoneRaw,
            phoneNormalized = phoneNormalized,
            email = emailTrimmed,
            address = r.address?.trim(),
            birthDate = r.birthDate?.let { java.time.LocalDate.parse(it) },
            language = r.language?.trim(),
            documents = mutableListOf()
        )
        patientProfile.user = user
        patients.save(patientProfile)

        val principal = emailTrimmed
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
    data class ChangePasswordRequest(
        val currentPassword: String? = null,
        @field:NotBlank val newPassword: String
    )

    // ---------- Forgot Password ----------
    data class ForgotPasswordResetRequest(
        val idToken: String? = null,
        val email: String? = null,
        val emailOtp: String? = null,
        val app: String? = null,
        @field:NotBlank val newPassword: String
    )

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
            // New flow: email OTP verification
            val emailNorm = r.email.trim().lowercase()
            if (!emailOtpService.verify(emailNorm, r.emailOtp.trim(),
                    com.shifa.domain.EmailVerificationCode.PURPOSE_FORGOT_PASSWORD)) {
                throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired verification code")
            }
            users.findByEmail(emailNorm).orElse(null)
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
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide either email+emailOtp or idToken")
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
        user.passwordHash = encoder.encode(r.newPassword)
        user.forcePasswordReset = false
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

        user.passwordHash = encoder.encode(r.newPassword)
        user.forcePasswordReset = false
        users.save(user)

        return mapOf("message" to "Password updated successfully")
    }

    private fun resolveUserForLogin(username: String, request: jakarta.servlet.http.HttpServletRequest): User {
        return try {
            users.findByUsername(username).orElseGet {
                users.findByEmail(username).orElseGet {
                    users.findByPhone(username).orElseThrow {
                        val clientIp = request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
                            ?: request.remoteAddr
                        log.warn("Failed login attempt (unknown identifier) from ip={} path={}", clientIp, request.servletPath)
                        ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
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
                if (!hasDoctorRole || doctors.findByUserId(user.id).isEmpty) {
                    throw ResponseStatusException(HttpStatus.FORBIDDEN, "No doctor account found")
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
}
