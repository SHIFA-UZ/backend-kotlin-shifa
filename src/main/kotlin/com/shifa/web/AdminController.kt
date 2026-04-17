package com.shifa.web

import com.shifa.domain.User
import com.shifa.repo.*
import com.shifa.security.AdminPrincipal
import com.shifa.service.*
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import com.shifa.domain.AdminLevel
import java.time.OffsetDateTime
import java.util.NoSuchElementException

@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val invitationKeyService: InvitationKeyService,
    private val userManagementService: UserManagementService,
    private val userDeletionService: com.shifa.service.UserDeletionService,
    private val auditService: AuditService,
    private val userActivityService: UserActivityService,
    private val userRepository: UserRepository,
    private val doctorProfileRepository: DoctorProfileRepository,
    private val patientProfileRepository: PatientProfileRepository,
    private val systemConfigRepository: SystemConfigRepository,
    private val adminCalendarResetService: com.shifa.service.AdminCalendarResetService
) {
    
    // ==================== DASHBOARD ====================
    
    @GetMapping("/dashboard/stats")
    fun getDashboardStats(@AuthenticationPrincipal principal: AdminPrincipal): Map<String, Any> {
        val totalDoctors = userRepository.countByRole(com.shifa.domain.Role.DOCTOR)
        val activeDoctors = userRepository.countByRoleAndEnabled(com.shifa.domain.Role.DOCTOR, true)
        val totalPatients = patientProfileRepository.count()
        val totalUsers = userRepository.count()
        val activeTokens = invitationKeyService.findAllActive(
            org.springframework.data.domain.PageRequest.of(0, 1)
        ).totalElements
        
        return mapOf(
            "totalDoctors" to totalDoctors,
            "activeDoctors" to activeDoctors,
            "totalPatients" to totalPatients,
            "totalUsers" to totalUsers,
            "activeTokens" to activeTokens
        )
    }
    
    // ==================== INVITATION KEY MANAGEMENT ====================
    
    data class GenerateTokenRequest(
        val expiresInDays: Int? = null,
        val purpose: String = "DOCTOR_ONBOARDING",
        val notes: String? = null,
        val sendEmail: Boolean = false,
        @field:Email val emailTo: String? = null
    )
    
    data class TokenResponse(
        val id: Long,
        val keyCode: String,
        val consumed: Boolean,
        val expiresAt: String?,
        val purpose: String,
        val notes: String?,
        val emailSentTo: String?,
        val emailSentAt: String?,
        val createdAt: String
    )
    
    @PostMapping("/tokens/generate")
    fun generateToken(
        @RequestBody request: GenerateTokenRequest,
        @AuthenticationPrincipal principal: AdminPrincipal,
        httpRequest: HttpServletRequest
    ): TokenResponse {
        if (principal.isReadOnly()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Read-only admins cannot generate tokens")
        }
        
        val adminUser = principal.adminProfile.user
        val key = invitationKeyService.generateKey(
            createdBy = adminUser,
            expiresInDays = request.expiresInDays,
            purpose = request.purpose,
            notes = request.notes
        )
        
        // TODO: Send email if requested
        if (request.sendEmail && request.emailTo != null) {
            // invitationKeyService.markEmailSent(key.id, request.emailTo)
            // emailService.sendInvitationEmail(request.emailTo, key.keyCode)
        }
        
        auditService.logAction(
            adminUser = adminUser,
            actionType = "TOKEN_GENERATED",
            entityType = "INVITATION_KEY",
            entityId = key.id,
            details = mapOf(
                "keyCode" to key.keyCode,
                "purpose" to request.purpose,
                "expiresInDays" to (request.expiresInDays ?: "never")
            ),
            request = httpRequest
        )
        
        return toTokenResponse(key)
    }
    
    @GetMapping("/tokens")
    fun listTokens(
        @AuthenticationPrincipal principal: AdminPrincipal,
        @RequestParam(required = false) consumed: Boolean?,
        @RequestParam(required = false) purpose: String?,
        @PageableDefault(size = 20) pageable: Pageable
    ): Page<TokenResponse> {
        return try {
            val page = when {
                consumed != null -> invitationKeyService.findAllConsumed(consumed, pageable)
                purpose != null -> invitationKeyService.findByPurpose(purpose, pageable)
                else -> invitationKeyService.findAllActive(pageable)
            }
            val result = page.map { toTokenResponse(it) }
            org.slf4j.LoggerFactory.getLogger(AdminController::class.java)
                .debug("AdminController.listTokens: Returning page with ${result.content.size} tokens, total: ${result.totalElements}")
            result
        } catch (e: Exception) {
            org.slf4j.LoggerFactory.getLogger(AdminController::class.java)
                .error("AdminController.listTokens: Error occurred", e)
            throw e
        }
    }
    
    @GetMapping("/tokens/{tokenId}")
    fun getToken(@PathVariable tokenId: Long): TokenResponse {
        val key = invitationKeyService.findById(tokenId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Token not found") }
        return toTokenResponse(key)
    }
    
    @PostMapping("/tokens/{tokenId}/revoke")
    fun revokeToken(
        @PathVariable tokenId: Long,
        @AuthenticationPrincipal principal: AdminPrincipal,
        httpRequest: HttpServletRequest
    ): TokenResponse {
        if (principal.isReadOnly()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Read-only admins cannot revoke tokens")
        }
        
        val adminUser = principal.adminProfile.user
        val key = invitationKeyService.revokeKey(tokenId)
        
        auditService.logAction(
            adminUser = adminUser,
            actionType = "TOKEN_REVOKED",
            entityType = "INVITATION_KEY",
            entityId = key.id,
            request = httpRequest
        )
        
        return toTokenResponse(key)
    }
    
    @PostMapping("/tokens/{tokenId}/regenerate")
    fun regenerateToken(
        @PathVariable tokenId: Long,
        @RequestBody request: GenerateTokenRequest,
        @AuthenticationPrincipal principal: AdminPrincipal,
        httpRequest: HttpServletRequest
    ): TokenResponse {
        if (principal.isReadOnly()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Read-only admins cannot regenerate tokens")
        }
        
        val adminUser = principal.adminProfile.user
        val key = invitationKeyService.regenerateKey(
            oldKeyId = tokenId,
            createdBy = adminUser,
            expiresInDays = request.expiresInDays
        )
        
        auditService.logAction(
            adminUser = adminUser,
            actionType = "TOKEN_REGENERATED",
            entityType = "INVITATION_KEY",
            entityId = key.id,
            details = mapOf("oldTokenId" to tokenId),
            request = httpRequest
        )
        
        return toTokenResponse(key)
    }
    
    private fun toTokenResponse(key: com.shifa.domain.InvitationKey): TokenResponse {
        return TokenResponse(
            id = key.id,
            keyCode = key.keyCode,
            consumed = key.consumed,
            expiresAt = key.expiresAt?.toString(),
            purpose = key.purpose,
            notes = key.notes,
            emailSentTo = key.emailSentTo,
            emailSentAt = key.emailSentAt?.toString(),
            createdAt = key.createdAt.toString()
        )
    }
    
    // ==================== USER MANAGEMENT ====================
    
    data class UserResponse(
        val id: Long,
        val email: String?,
        val phone: String?,
        val role: String,
        val enabled: Boolean,
        val lastLoginAt: String?,
        val failedLoginAttempts: Int,
        val lockedUntil: String?,
        val profile: Map<String, Any>?
    )
    
    @GetMapping("/users")
    fun listUsers(
        @RequestParam(required = false) role: String?,
        @RequestParam(required = false) enabled: Boolean?,
        @RequestParam(required = false) search: String?,
        @PageableDefault(size = 20) pageable: Pageable
    ): Page<UserResponse> {
        val roleEnum = role?.let {
            try {
                com.shifa.domain.Role.valueOf(it.uppercase())
            } catch (e: IllegalArgumentException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid role: $role")
            }
        }
        val users = userManagementService.listUsers(roleEnum, enabled, search, pageable)
        return users.map { toUserResponse(it) }
    }
    
    @GetMapping("/users/{userId}")
    fun getUser(@PathVariable userId: Long): UserResponse {
        val user = userManagementService.getUserById(userId)
        return toUserResponse(user)
    }

    data class CreateAdminRequest(
        @NotBlank val email: String,
        @NotBlank val password: String,
        @NotBlank val firstName: String,
        @NotBlank val lastName: String,
        val adminLevel: String? = "ADMIN"
    )

    @PostMapping("/users/create-admin")
    fun createAdminUser(
        @RequestBody @Valid request: CreateAdminRequest,
        @AuthenticationPrincipal principal: AdminPrincipal,
        httpRequest: HttpServletRequest
    ): UserResponse {
        if (principal.isReadOnly()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Read-only admins cannot create admin users")
        }
        val adminLevel = try {
            request.adminLevel?.let { AdminLevel.valueOf(it.uppercase()) } ?: AdminLevel.ADMIN
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid adminLevel: ${request.adminLevel}")
        }
        val adminUser = principal.adminProfile.user
        val user = userManagementService.createAdminUser(
            email = request.email.trim(),
            password = request.password,
            firstName = request.firstName.trim(),
            lastName = request.lastName.trim(),
            adminLevel = adminLevel
        )
        auditService.logAction(
            adminUser = adminUser,
            actionType = "ADMIN_USER_CREATED",
            entityType = "USER",
            entityId = user.id,
            details = mapOf("email" to request.email),
            request = httpRequest
        )
        return toUserResponse(user)
    }
    
    @PostMapping("/users/{userId}/enable")
    fun enableUser(
        @PathVariable userId: Long,
        @RequestBody request: Map<String, Boolean>,
        @AuthenticationPrincipal principal: AdminPrincipal,
        httpRequest: HttpServletRequest
    ): UserResponse {
        if (principal.isReadOnly()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Read-only admins cannot modify users")
        }
        
        val adminUser = principal.adminProfile.user
        val enabled = request["enabled"] ?: true
        val user = userManagementService.setUserEnabled(userId, enabled)
        
        auditService.logAction(
            adminUser = adminUser,
            actionType = if (enabled) "USER_ACTIVATED" else "USER_DEACTIVATED",
            entityType = "USER",
            entityId = user.id,
            details = mapOf("userId" to userId),
            request = httpRequest
        )
        
        return toUserResponse(user)
    }
    
    @PostMapping("/users/{userId}/reset-password")
    fun resetPassword(
        @PathVariable userId: Long,
        @AuthenticationPrincipal principal: AdminPrincipal,
        httpRequest: HttpServletRequest
    ): Map<String, String> {
        if (principal.isReadOnly()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Read-only admins cannot reset passwords")
        }
        
        val adminUser = principal.adminProfile.user
        val tempPassword = userManagementService.resetPassword(userId)
        
        auditService.logAction(
            adminUser = adminUser,
            actionType = "PASSWORD_RESET",
            entityType = "USER",
            entityId = userId,
            request = httpRequest
        )
        
        // TODO: Send email with temporary password
        return mapOf("temporaryPassword" to tempPassword)
    }
    
    @PostMapping("/users/{userId}/force-logout")
    fun forceLogout(
        @PathVariable userId: Long,
        @AuthenticationPrincipal principal: AdminPrincipal,
        httpRequest: HttpServletRequest
    ): Map<String, String> {
        if (principal.isReadOnly()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Read-only admins cannot force logout")
        }
        
        val adminUser = principal.adminProfile.user
        userManagementService.forceLogout(userId, adminUser)
        
        auditService.logAction(
            adminUser = adminUser,
            actionType = "FORCE_LOGOUT",
            entityType = "USER",
            entityId = userId,
            request = httpRequest
        )
        
        return mapOf("message" to "User sessions revoked")
    }
    
    @PostMapping("/users/{userId}/unlock")
    fun unlockUser(
        @PathVariable userId: Long,
        @AuthenticationPrincipal principal: AdminPrincipal,
        httpRequest: HttpServletRequest
    ): UserResponse {
        if (principal.isReadOnly()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Read-only admins cannot unlock users")
        }
        
        val adminUser = principal.adminProfile.user
        val user = userManagementService.unlockUser(userId)
        
        auditService.logAction(
            adminUser = adminUser,
            actionType = "USER_UNLOCKED",
            entityType = "USER",
            entityId = user.id,
            request = httpRequest
        )
        
        return toUserResponse(user)
    }

    /**
     * Permanently delete a user and all related data so the phone number (and email) can be used to create a new account.
     * Cannot delete ADMIN users.
     */
    @DeleteMapping("/users/{userId}")
    fun deleteUser(
        @PathVariable userId: Long,
        @AuthenticationPrincipal principal: AdminPrincipal,
        httpRequest: HttpServletRequest
    ): Map<String, Any> {
        if (principal.isReadOnly()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Read-only admins cannot delete users")
        }
        val adminUser = principal.adminProfile.user
        val user = userManagementService.getUserById(userId)
        userDeletionService.deleteUser(userId)
        auditService.logAction(
            adminUser = adminUser,
            actionType = "USER_DELETED",
            entityType = "USER",
            entityId = userId,
            details = mapOf(
                "deletedPhone" to (user.phone ?: ""),
                "deletedEmail" to (user.email ?: ""),
                "deletedRole" to user.role.name
            ),
            request = httpRequest
        )
        return mapOf(
            "message" to "User and all related data deleted. Phone and email can be used for a new account.",
            "userId" to userId
        )
    }
    
    // ==================== DOCTOR CALENDAR RESET (ADMIN-ONLY) ====================

    /**
     * Permanently delete all calendar-related data for a doctor:
     * appointments (past & future), weekly schedule rules, date-specific schedule rules.
     * Does NOT change credentials, profile, or patient data.
     */
    @PostMapping("/doctors/{doctorId}/reset-calendar")
    fun resetDoctorCalendar(
        @PathVariable doctorId: Long,
        @AuthenticationPrincipal principal: AdminPrincipal,
        httpRequest: HttpServletRequest
    ): Map<String, Any> {
        if (principal.isReadOnly()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Read-only admins cannot reset doctor calendars")
        }

        val adminUser = principal.adminProfile.user
        return try {
            val doctor = adminCalendarResetService.resetDoctorCalendar(doctorId)
            auditService.logAction(
                adminUser = adminUser,
                actionType = "RESET_DOCTOR_CALENDAR",
                entityType = "DOCTOR_PROFILE",
                entityId = doctorId,
                details = mapOf(
                    "doctorUserId" to doctor.user.id,
                    "doctorName" to "${doctor.firstName} ${doctor.lastName}"
                ),
                request = httpRequest
            )
            mapOf(
                "message" to "Doctor calendar reset successfully",
                "doctorId" to doctorId,
                "doctorUserId" to doctor.user.id
            )
        } catch (e: NoSuchElementException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found: $doctorId")
        }
    }

    @GetMapping("/users/{userId}/sessions")
    fun getUserSessions(@PathVariable userId: Long): List<Map<String, Any>> {
        val sessions = userManagementService.getActiveSessions(userId)
        return sessions.map {
            mapOf(
                "id" to it.id,
                "ipAddress" to (it.ipAddress ?: ""),
                "userAgent" to (it.userAgent ?: ""),
                "createdAt" to it.createdAt.toString(),
                "expiresAt" to it.expiresAt.toString(),
                "revoked" to it.revoked
            )
        }
    }
    
    private fun toUserResponse(user: User): UserResponse {
        val profile = userManagementService.getUserProfileInfo(user.id)
        return UserResponse(
            id = user.id,
            email = user.email,
            phone = user.phone,
            role = user.role.name,
            enabled = user.enabled,
            lastLoginAt = user.lastLoginAt?.toString(),
            failedLoginAttempts = user.failedLoginAttempts ?: 0,
            lockedUntil = user.lockedUntil?.toString(),
            profile = profile
        )
    }
    
    // ==================== AUDIT LOGS ====================
    
    @GetMapping("/audit-logs")
    fun getAuditLogs(
        @RequestParam(required = false) adminUserId: Long?,
        @RequestParam(required = false) entityType: String?,
        @RequestParam(required = false) entityId: Long?,
        @RequestParam(required = false) actionType: String?,
        @PageableDefault(size = 50) pageable: Pageable
    ): Page<Map<String, Any>> {
        val page = when {
            adminUserId != null -> auditService.getAdminActivity(adminUserId, pageable)
            entityType != null && entityId != null -> auditService.getEntityActivity(entityType, entityId, pageable)
            actionType != null -> auditService.getActivityByType(actionType, OffsetDateTime.now().minusDays(30), pageable)
            else -> auditService.getActivityBetween(OffsetDateTime.now().minusDays(30), OffsetDateTime.now(), pageable)
        }
        
        return page.map {
            mapOf(
                "id" to it.id,
                "adminUserId" to it.adminUser.id,
                "actionType" to it.actionType,
                "entityType" to it.entityType,
                "entityId" to (it.entityId ?: ""),
                "details" to (it.details ?: emptyMap<String, Any>()),
                "ipAddress" to (it.ipAddress ?: ""),
                "userAgent" to (it.userAgent ?: ""),
                "createdAt" to it.createdAt.toString()
            )
        }
    }
    
    @GetMapping("/activity-logs")
    fun getActivityLogs(
        @RequestParam(required = false) userId: Long?,
        @RequestParam(required = false) activityType: String?,
        @PageableDefault(size = 50) pageable: Pageable
    ): Page<Map<String, Any>> {
        val page = when {
            userId != null -> userActivityService.getUserActivity(userId, pageable)
            activityType != null -> userActivityService.getActivityByType(activityType, OffsetDateTime.now().minusDays(30), pageable)
            else -> userActivityService.getActivityBetween(OffsetDateTime.now().minusDays(30), OffsetDateTime.now(), pageable)
        }
        
        return page.map {
            mapOf(
                "id" to it.id,
                "userId" to it.user.id,
                "activityType" to it.activityType,
                "ipAddress" to (it.ipAddress ?: ""),
                "userAgent" to (it.userAgent ?: ""),
                "success" to it.success,
                "failureReason" to (it.failureReason ?: ""),
                "createdAt" to it.createdAt.toString()
            )
        }
    }
    
    // ==================== SYSTEM CONFIG ====================
    
    @GetMapping("/config")
    fun getSystemConfig(): Map<String, String> {
        val configs = systemConfigRepository.findAll()
        return configs.associate { it.key to it.value }
    }
    
    @GetMapping("/config/{key}")
    fun getConfigValue(@PathVariable key: String): Map<String, String> {
        val config = systemConfigRepository.findByKey(key)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Config not found") }
        return mapOf("key" to config.key, "value" to config.value, "description" to (config.description ?: ""))
    }
    
    @PutMapping("/config/{key}")
    fun updateConfig(
        @PathVariable key: String,
        @RequestBody request: Map<String, String>,
        @AuthenticationPrincipal principal: AdminPrincipal,
        httpRequest: HttpServletRequest
    ): Map<String, String> {
        if (principal.isReadOnly() || !principal.isSuperAdmin()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only super admins can update config")
        }
        
        val adminUser = principal.adminProfile.user
        val config = systemConfigRepository.findByKey(key)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Config not found") }
        
        config.value = request["value"] ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Value required")
        config.updatedAt = OffsetDateTime.now()
        systemConfigRepository.save(config)
        
        auditService.logAction(
            adminUser = adminUser,
            actionType = "CONFIG_UPDATED",
            entityType = "SYSTEM_CONFIG",
            entityId = config.id,
            details = mapOf("key" to key, "oldValue" to config.value, "newValue" to request["value"]!!),
            request = httpRequest
        )
        
        return mapOf("key" to config.key, "value" to config.value)
    }
}
