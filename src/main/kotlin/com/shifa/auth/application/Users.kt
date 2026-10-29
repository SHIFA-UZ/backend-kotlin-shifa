package com.shifa.auth.application

import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import com.shifa.auth.infrastructure.UserRepository
import java.util.*
import java.util.Optional
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service

/**
 * The feature's door to User records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [UserRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class Users(private val repo: UserRepository) {
    fun findByEmail(email: String): Optional<User> = repo.findByEmail(email)
    fun findByEmailIgnoreCase(email: String): Optional<User> = repo.findByEmailIgnoreCase(email)
    fun findByPhone(phone: String): Optional<User> = repo.findByPhone(phone)
    fun findByUsername(username: String): Optional<User> = repo.findByUsername(username)
    fun findByRole(role: Role, pageable: Pageable): Page<User> = repo.findByRole(role, pageable)
    fun countByRole(role: Role): Long = repo.countByRole(role)
    fun countByRoleAndEnabled(role: Role, enabled: Boolean): Long = repo.countByRoleAndEnabled(role, enabled)
    fun searchByRoleAndQuery(excludeUserId: Long, role: Role, query: String, pageable: org.springframework.data.domain.Pageable): List<User> = repo.searchByRoleAndQuery(excludeUserId, role, query, pageable)
    fun findUserIdsBySearch(q: String, role: Role?, enabled: Boolean?): List<Long> = repo.findUserIdsBySearch(q, role, enabled)
    fun findByPhoneOriginalHashAndAccountStatus(phoneOriginalHash: String, accountStatus: User.AccountStatus = User.AccountStatus.DELETED): Optional<User> = repo.findByPhoneOriginalHashAndAccountStatus(phoneOriginalHash, accountStatus)
    fun findByEmailOriginalHashAndAccountStatus(emailOriginalHash: String, accountStatus: User.AccountStatus = User.AccountStatus.DELETED): Optional<User> = repo.findByEmailOriginalHashAndAccountStatus(emailOriginalHash, accountStatus)
    fun count(): Long = repo.count()
    fun findAllById(ids: Iterable<Long>): List<User> = repo.findAllById(ids)
    fun findById(id: Long): Optional<User> = repo.findById(id)
    fun save(entity: User): User = repo.save(entity)
}
