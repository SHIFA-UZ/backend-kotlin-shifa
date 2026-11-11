package com.shifa.auth.application

import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import com.shifa.auth.domain.UserRole
import com.shifa.auth.infrastructure.UserRoleRepository
import org.springframework.stereotype.Service

/**
 * The feature's door to UserRole records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [UserRoleRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class UserRoles(private val repo: UserRoleRepository) {
    fun findByUserId(userId: Long): List<UserRole> = repo.findByUserId(userId)
    fun existsByUserIdAndRole(userId: Long, role: Role): Boolean = repo.existsByUserIdAndRole(userId, role)
    fun save(entity: UserRole): UserRole = repo.save(entity)

    /** The account's roles: its user_roles rows, or users.role for an account from before multi-role (V40). */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun rolesOf(user: User): Set<Role> = repo.findByUserId(user.id).map { it.role }.toSet().ifEmpty { setOf(user.role) }

    /**
     * Takes one role off an account that keeps others (deleting one app's side). users.role follows the first one
     * left, so a sign-in without an app hint gets a role the account still has.
     */
    @org.springframework.transaction.annotation.Transactional
    fun remove(user: User, role: Role) {
        repo.findByUserIdAndRole(user.id, role).ifPresent(repo::delete)
        val left = rolesOf(user) - role
        if (user.role == role) user.role = ROLE_ORDER.firstOrNull { it in left } ?: user.role
    }

    private companion object {
        val ROLE_ORDER = listOf(Role.DOCTOR, Role.CLINIC_STAFF, Role.PATIENT, Role.ADMIN)
    }
}
