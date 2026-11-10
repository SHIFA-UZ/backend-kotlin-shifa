package com.shifa.auth.application

import com.shifa.admin.domain.AdminLevel
import com.shifa.admin.domain.AdminProfile
import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import com.shifa.auth.domain.UserRole
import com.shifa.auth.infrastructure.UserRepository
import com.shifa.auth.infrastructure.UserRoleRepository
import com.shifa.auth.infrastructure.UserSessionRepository
import com.shifa.shared.security.UserSessionValidationCache
import java.time.OffsetDateTime
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class UserManagementService(
    private val userRepository: UserRepository,
    private val adminProfileRepository: com.shifa.admin.application.AdminProfiles,
    private val doctorProfileRepository: com.shifa.doctors.application.DoctorProfiles,
    private val patientProfileRepository: com.shifa.patients.application.PatientProfiles,
    private val userRoleRepository: UserRoleRepository,
    private val userSessionRepository: UserSessionRepository,
    private val passwordEncoder: PasswordEncoder,
    private val sessionValidationCache: UserSessionValidationCache,
) {
    
    /**
     * Create a new admin user (role ADMIN) with an admin profile.
     * Only creates admin users; email must be unique.
     */
    @Transactional
    fun createAdminUser(
        email: String,
        password: String,
        firstName: String,
        lastName: String,
        adminLevel: AdminLevel = AdminLevel.ADMIN
    ): User {
        // Case-insensitive, as the address is stored lower-cased: one answer whatever the spelling (409, as registration).
        if (userRepository.findByEmailIgnoreCase(email.trim()).isPresent) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Email already registered")
        }
        val user = User(
            email = email.trim().lowercase(),
            passwordHash = passwordEncoder.encode(password)!!,
            role = Role.ADMIN,
            enabled = true
        )
        val savedUser = userRepository.save(user)
        val profile = AdminProfile(
            user = savedUser,
            firstName = firstName.trim(),
            lastName = lastName.trim(),
            adminLevel = adminLevel
        )
        adminProfileRepository.save(profile)
        // Login with ?app=admin checks user_roles; mirror other flows (register, etc.)
        if (!userRoleRepository.existsByUserIdAndRole(savedUser.id, Role.ADMIN)) {
            userRoleRepository.save(UserRole(user = savedUser, role = Role.ADMIN))
        }
        return savedUser
    }

    /**
     * The admin user list. Every filter (role, enabled, search over phone, e-mail, username and profile names,
     * device) is applied in one query, so the page and the total are right for any combination; the order is the
     * pageable's sort, newest first by default (see [com.shifa.auth.infrastructure.UserRepositoryCustom]).
     */
    @Transactional(readOnly = true)
    fun listUsers(
        role: Role? = null,
        enabled: Boolean? = null,
        search: String? = null,
        deviceRegistered: Boolean? = null,
        pageable: Pageable
    ): Page<User> = userRepository.searchUsersAdmin(search, role, enabled, deviceRegistered, pageable)

    /** Aggregate metrics for the admin User Management dashboard. */
    @Transactional(readOnly = true)
    fun getUserManagementStats(): Map<String, Any> {
        val totalUsers = userRepository.count()
        val totalDoctors = userRepository.countByRole(Role.DOCTOR)
        val activeDoctors = userRepository.countByRoleAndEnabled(Role.DOCTOR, true)
        val disabledDoctors = totalDoctors - activeDoctors
        val patientAppUsers = userRepository.countByRole(Role.PATIENT)
        val activePatientUsers = userRepository.countByRoleAndEnabled(Role.PATIENT, true)
        val totalAdmins = userRepository.countByRole(Role.ADMIN)
        val totalPatientProfiles = patientProfileRepository.count()
        val profilesWithoutAppAccount = patientProfileRepository.countByUserIsNull()
        val profilesWithAppAccount = patientProfileRepository.countByUserIsNotNull()
        // The same query as the list's device filter, so each number equals the list's total for that filter and
        // with-device plus without-device is the role's account count (accounts without a profile have no device).
        val patientsWithDevice = userRepository.countUsersAdmin(null, Role.PATIENT, null, true)
        val patientAppUsersWithoutDevice = userRepository.countUsersAdmin(null, Role.PATIENT, null, false)
        val doctorsWithDevice = userRepository.countUsersAdmin(null, Role.DOCTOR, null, true)
        val doctorsWithoutDevice = userRepository.countUsersAdmin(null, Role.DOCTOR, null, false)
        val patientsNeverLoggedIn = userRepository.countByRoleAndLastLoginAtIsNull(Role.PATIENT)
        val patientsLoggedIn = userRepository.countByRoleAndLastLoginAtIsNotNull(Role.PATIENT)
        val doctorsNeverLoggedIn = userRepository.countByRoleAndLastLoginAtIsNull(Role.DOCTOR)
        val deviceActivationRate = if (patientAppUsers > 0) {
            ((patientsWithDevice.toDouble() / patientAppUsers) * 100).toInt()
        } else 0

        return mapOf(
            "totalUsers" to totalUsers,
            "totalDoctors" to totalDoctors,
            "activeDoctors" to activeDoctors,
            "disabledDoctors" to disabledDoctors,
            "patientAppUsers" to patientAppUsers,
            "activePatientUsers" to activePatientUsers,
            "totalAdmins" to totalAdmins,
            "totalPatientProfiles" to totalPatientProfiles,
            "profilesWithoutAppAccount" to profilesWithoutAppAccount,
            "profilesWithAppAccount" to profilesWithAppAccount,
            "patientsWithDevice" to patientsWithDevice,
            "patientAppUsersWithoutDevice" to patientAppUsersWithoutDevice,
            "doctorsWithDevice" to doctorsWithDevice,
            "doctorsWithoutDevice" to doctorsWithoutDevice,
            "patientsNeverLoggedIn" to patientsNeverLoggedIn,
            "patientsLoggedIn" to patientsLoggedIn,
            "doctorsNeverLoggedIn" to doctorsNeverLoggedIn,
            "deviceActivationRate" to deviceActivationRate,
        )
    }

    @Transactional(readOnly = true)
    fun isDeviceRegistered(user: User): Boolean {
        return when (user.role) {
            Role.PATIENT -> patientProfileRepository.findByUserId(user.id)
                .map { !it.fcmToken.isNullOrBlank() }
                .orElse(false)
            Role.DOCTOR -> doctorProfileRepository.findByUserId(user.id)
                .map { !it.fcmToken.isNullOrBlank() }
                .orElse(false)
            else -> false
        }
    }

    @Transactional(readOnly = true)
    fun hasProfile(user: User): Boolean {
        return when (user.role) {
            Role.PATIENT -> patientProfileRepository.findByUserId(user.id).isPresent
            Role.DOCTOR -> doctorProfileRepository.findByUserId(user.id).isPresent
            Role.ADMIN -> adminProfileRepository.findByUserId(user.id).isPresent
            else -> false
        }
    }

    /**
     * Get user by ID
     */
    @Transactional(readOnly = true)
    fun getUserById(userId: Long): User {
        return userRepository.findById(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }
    }
    
    /**
     * Activate/deactivate user
     */
    @Transactional
    fun setUserEnabled(userId: Long, enabled: Boolean): User {
        val user = getUserById(userId)
        user.enabled = enabled
        return userRepository.save(user)
    }
    
    /**
     * Reset user password (generates temporary password)
     */
    @Transactional
    fun resetPassword(userId: Long): String {
        val user = getUserById(userId)
        // Generate temporary password (8-12 chars, alphanumeric)
        val tempPassword = generateTemporaryPassword()
        user.passwordHash = passwordEncoder.encode(tempPassword)!!
        userRepository.save(user)
        revokeSessionsExcept(userId, keepJti = null)
        return tempPassword
    }
    
    /**
     * Force logout - revoke all active sessions
     */
    @Transactional
    fun forceLogout(userId: Long, revokedBy: User? = null) {
        getUserById(userId)
        val now = OffsetDateTime.now()
        val jtis = userSessionRepository.findActiveSessionsByUserId(userId, now)
            .mapNotNull { it.tokenJti }
        userSessionRepository.revokeAllUserSessions(userId, now)
        sessionValidationCache.invalidateAll(jtis)
    }
    
    /**
     * After a password change: every other session of the user ends, so a stolen token dies with the old password.
     * [keepJti] is the caller's own session (null after a forgotten-password reset, which signs in anew).
     */
    @Transactional
    fun revokeSessionsExcept(userId: Long, keepJti: String?) {
        val now = OffsetDateTime.now()
        val others = userSessionRepository.findActiveSessionsByUserId(userId, now).filter { it.tokenJti != keepJti }
        others.forEach {
            it.revoked = true
            it.revokedAt = now
        }
        userSessionRepository.saveAll(others)
        sessionValidationCache.invalidateAll(others.mapNotNull { it.tokenJti })
    }

    /**
     * Unlock user account (clear lockout)
     */
    @Transactional
    fun unlockUser(userId: Long): User {
        val user = getUserById(userId)
        user.lockedUntil = null
        user.failedLoginAttempts = 0
        return userRepository.save(user)
    }
    
    /**
     * Update last login timestamp
     */
    @Transactional
    fun updateLastLogin(userId: Long) {
        val user = getUserById(userId)
        user.lastLoginAt = OffsetDateTime.now()
        user.failedLoginAttempts = 0 // Reset on successful login
        userRepository.save(user)
    }
    
    /**
     * Increment failed login attempts
     */
    @Transactional
    fun incrementFailedLoginAttempts(userId: Long, maxAttempts: Int = 5, lockoutMinutes: Int = 30) {
        val user = getUserById(userId)
        user.failedLoginAttempts = (user.failedLoginAttempts ?: 0) + 1
        
        if (user.failedLoginAttempts >= maxAttempts) {
            user.lockedUntil = OffsetDateTime.now().plusMinutes(lockoutMinutes.toLong())
        }
        
        userRepository.save(user)
    }
    
    /**
     * Get user's active sessions
     */
    @Transactional(readOnly = true)
    fun getActiveSessions(userId: Long): List<com.shifa.auth.domain.UserSession> {
        return userSessionRepository.findActiveSessionsByUserId(userId, OffsetDateTime.now())
    }
    
    /**
     * Get user's session history
     */
    @Transactional(readOnly = true)
    fun getSessionHistory(userId: Long): List<com.shifa.auth.domain.UserSession> {
        return userSessionRepository.findByUserIdOrderByCreatedAtDesc(userId)
    }
    
    /**
     * Generate temporary password
     */
    private fun generateTemporaryPassword(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = java.security.SecureRandom()
        return (1..12)
            .map { chars[random.nextInt(chars.length)] }
            .joinToString("")
    }
    
    /**
     * Get user profile info (doctor or patient)
     */
    @Transactional(readOnly = true)
    fun getUserProfileInfo(userId: Long): Map<String, Any> {
        val user = getUserById(userId)
        val info = mutableMapOf<String, Any>(
            "userId" to user.id,
            "email" to (user.email ?: ""),
            "phone" to (user.phone ?: ""),
            "role" to user.role.name,
            "enabled" to user.enabled,
            "lastLoginAt" to (user.lastLoginAt?.toString() ?: ""),
            "failedLoginAttempts" to (user.failedLoginAttempts ?: 0),
            "lockedUntil" to (user.lockedUntil?.toString() ?: "")
        )
        
        when (user.role) {
            Role.DOCTOR -> {
                doctorProfileRepository.findByUserId(userId).ifPresent { doctor ->
                    info["doctorId"] = doctor.id
                    info["firstName"] = doctor.firstName
                    info["lastName"] = doctor.lastName
                    info["clinic"] = doctor.clinic ?: ""
                    info["deviceRegistered"] = !doctor.fcmToken.isNullOrBlank()
                }
            }
            Role.ADMIN -> {
                adminProfileRepository.findByUserId(userId).ifPresent { admin ->
                    info["firstName"] = admin.firstName
                    info["lastName"] = admin.lastName
                    info["adminLevel"] = admin.adminLevel.name
                }
            }
            Role.PATIENT -> {
                patientProfileRepository.findByUserId(userId).ifPresent { patient ->
                    info["fullName"] = patient.fullName
                    // Flutter displayName uses firstName/lastName; expose as single name for patients
                    info["firstName"] = patient.fullName
                    info["lastName"] = ""
                    info["deviceRegistered"] = !patient.fcmToken.isNullOrBlank()
                    info["profileCreatedAt"] = patient.createdAt?.toString() ?: ""
                }
            }
            else -> {}
        }
        
        return info
    }

    data class AdminPatientProfileRow(
        val patientProfileId: Long,
        val fullName: String,
        val phone: String?,
        val email: String?,
        val createdAt: String?,
        val createdByDoctorId: Long?,
        val createdByDoctorName: String?,
    )

    @Transactional(readOnly = true)
    fun listPatientProfilesWithoutAppAccount(
        search: String? = null,
        pageable: Pageable,
    ): Page<AdminPatientProfileRow> {
        val searchTrimmed = search?.trim()?.takeIf { it.isNotBlank() }
        return patientProfileRepository.findWithoutAppAccount(searchTrimmed, pageable)
            .map { profile -> toAdminPatientProfileRow(profile) }
    }

    private fun toAdminPatientProfileRow(profile: com.shifa.patients.domain.PatientProfile): AdminPatientProfileRow {
        val doctor = profile.createdByDoctor
        val doctorName = doctor?.let { "${it.firstName.trim()} ${it.lastName.trim()}".trim().takeIf { n -> n.isNotEmpty() } }
        return AdminPatientProfileRow(
            patientProfileId = profile.id ?: 0L,
            fullName = profile.fullName.trim(),
            phone = profile.phone?.trim()?.takeUnless { it.isEmpty() },
            email = profile.email?.trim()?.takeUnless { it.isEmpty() },
            createdAt = profile.createdAt?.toString(),
            createdByDoctorId = doctor?.id,
            createdByDoctorName = doctorName,
        )
    }
}
