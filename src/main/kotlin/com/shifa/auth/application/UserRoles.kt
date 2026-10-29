package com.shifa.auth.application

import com.shifa.auth.domain.Role
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
}
