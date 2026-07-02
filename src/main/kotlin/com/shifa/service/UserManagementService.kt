package com.shifa.service

import com.shifa.domain.AdminLevel
import com.shifa.domain.AdminProfile
import com.shifa.domain.Role
import com.shifa.domain.User
import com.shifa.domain.UserRole
import com.shifa.repo.AdminProfileRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.repo.UserRepository
import com.shifa.repo.UserRoleRepository
import com.shifa.repo.UserSessionRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.*

@Service
class UserManagementService(
    private val userRepository: UserRepository,
    private val adminProfileRepository: AdminProfileRepository,
    private val doctorProfileRepository: DoctorProfileRepository,
    private val patientProfileRepository: PatientProfileRepository,
    private val userRoleRepository: UserRoleRepository,
    private val userSessionRepository: UserSessionRepository,
    private val passwordEncoder: PasswordEncoder
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
        if (userRepository.findByEmail(email).isPresent) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Email already registered")
        }
        val user = User(
            email = email.trim().lowercase(),
            passwordHash = passwordEncoder.encode(password),
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
     * Get all users with pagination and filtering.
     * When search is not blank, searches phone, email, username, and name (doctor/patient/admin profile).
     * Uses JPQL only (no native SQL) so search is reliable across databases.
     */
    fun listUsers(
        role: Role? = null,
        enabled: Boolean? = null,
        search: String? = null,
        deviceRegistered: Boolean? = null,
        pageable: Pageable
    ): Page<User> {
        val searchTrimmed = search?.trim()?.takeIf { it.isNotBlank() }
        if (deviceRegistered != null && searchTrimmed == null) {
            return listUsersByDeviceFilter(role, enabled, deviceRegistered, pageable)
        }
        val basePage = if (searchTrimmed != null) {
            searchUsersAdminJpql(searchTrimmed, role, enabled, pageable)
        } else {
            when {
                role != null && enabled != null -> userRepository.findByRoleAndEnabled(role, enabled, pageable)
                role != null -> userRepository.findByRole(role, pageable)
                enabled != null -> userRepository.findByEnabled(enabled, pageable)
                else -> userRepository.findAll(pageable)
            }
        }
        if (deviceRegistered == null || searchTrimmed == null) return basePage
        val filtered = basePage.content.filter { isDeviceRegistered(it) == deviceRegistered }
        return org.springframework.data.domain.PageImpl(filtered, pageable, filtered.size.toLong())
    }

    /** Aggregate metrics for the admin User Management dashboard. */
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
        val patientsWithDevice = patientProfileRepository.countWithDeviceRegistered()
        val patientAppUsersWithoutDevice = patientProfileRepository.countAppUsersWithoutDevice()
        val doctorsWithDevice = doctorProfileRepository.countWithDeviceRegistered()
        val doctorsWithoutDevice = (totalDoctors - doctorsWithDevice).coerceAtLeast(0)
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

    fun hasProfile(user: User): Boolean {
        return when (user.role) {
            Role.PATIENT -> patientProfileRepository.findByUserId(user.id).isPresent
            Role.DOCTOR -> doctorProfileRepository.findByUserId(user.id).isPresent
            Role.ADMIN -> adminProfileRepository.findByUserId(user.id).isPresent
            else -> false
        }
    }

    private fun listUsersByDeviceFilter(
        role: Role?,
        enabled: Boolean?,
        deviceRegistered: Boolean,
        pageable: Pageable
    ): Page<User> {
        val candidateIds: List<Long> = when (role) {
            Role.PATIENT -> if (deviceRegistered) {
                patientProfileRepository.findUserIdsWithDeviceRegistered()
            } else {
                userRepository.findPatientUserIdsWithoutDeviceRegistered()
            }
            Role.DOCTOR -> if (deviceRegistered) {
                doctorProfileRepository.findUserIdsWithDeviceRegistered()
            } else {
                userRepository.findDoctorUserIdsWithoutDeviceRegistered()
            }
            null -> {
                val withDevice = patientProfileRepository.findUserIdsWithDeviceRegistered() +
                    doctorProfileRepository.findUserIdsWithDeviceRegistered()
                val withoutDevice = userRepository.findPatientUserIdsWithoutDeviceRegistered() +
                    userRepository.findDoctorUserIdsWithoutDeviceRegistered()
                if (deviceRegistered) withDevice.distinct() else withoutDevice.distinct()
            }
            else -> emptyList()
        }
        if (candidateIds.isEmpty()) {
            return org.springframework.data.domain.PageImpl(emptyList(), pageable, 0L)
        }
        val users = userRepository.findAllById(candidateIds)
        val filtered = users.filter { user ->
            (enabled == null || user.enabled == enabled) &&
                (role == null || user.role == role)
        }.sortedByDescending { it.createdAt }
        val total = filtered.size.toLong()
        val page = filtered.drop(pageable.offset.toInt()).take(pageable.pageSize)
        return org.springframework.data.domain.PageImpl(page, pageable, total)
    }

    /** Search by phone/email/username and profile names using JPQL only; merge, filter by role/enabled, paginate. */
    private fun searchUsersAdminJpql(
        q: String,
        role: Role?,
        enabled: Boolean?,
        pageable: Pageable
    ): Page<User> {
        val idsFromUser = userRepository.findUserIdsBySearch(q, role, enabled)
        val idsFromDoctor = doctorProfileRepository.findUserIdsBySearch(q)
        val idsFromPatient = patientProfileRepository.findUserIdsBySearch(q)
        val idsFromAdmin = adminProfileRepository.findUserIdsBySearch(q)
        val mergedIds = (idsFromUser + idsFromDoctor + idsFromPatient + idsFromAdmin).distinct()
        if (mergedIds.isEmpty()) return org.springframework.data.domain.PageImpl(emptyList(), pageable, 0L)
        val users = userRepository.findAllById(mergedIds)
        val filtered = if (role != null || enabled != null) {
            users.filter { (role == null || it.role == role) && (enabled == null || it.enabled == enabled) }
        } else users
        val sorted = filtered.sortedBy { it.id }
        val total = sorted.size.toLong()
        val page = sorted.drop(pageable.offset.toInt()).take(pageable.pageSize)
        return org.springframework.data.domain.PageImpl(page, pageable, total)
    }
    
    /**
     * Get user by ID
     */
    fun getUserById(userId: Long): User {
        return userRepository.findById(userId)
            .orElseThrow { NoSuchElementException("User not found: $userId") }
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
        user.passwordHash = passwordEncoder.encode(tempPassword)
        userRepository.save(user)
        return tempPassword
    }
    
    /**
     * Force logout - revoke all active sessions
     */
    @Transactional
    fun forceLogout(userId: Long, revokedBy: User? = null) {
        val user = getUserById(userId)
        val now = OffsetDateTime.now()
        userSessionRepository.revokeAllUserSessions(userId, now)
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
    fun getActiveSessions(userId: Long): List<com.shifa.domain.UserSession> {
        return userSessionRepository.findActiveSessionsByUserId(userId, OffsetDateTime.now())
    }
    
    /**
     * Get user's session history
     */
    fun getSessionHistory(userId: Long): List<com.shifa.domain.UserSession> {
        return userSessionRepository.findByUserIdOrderByCreatedAtDesc(userId)
    }
    
    /**
     * Generate temporary password
     */
    private fun generateTemporaryPassword(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..12)
            .map { chars.random() }
            .joinToString("")
    }
    
    /**
     * Get user profile info (doctor or patient)
     */
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
}
