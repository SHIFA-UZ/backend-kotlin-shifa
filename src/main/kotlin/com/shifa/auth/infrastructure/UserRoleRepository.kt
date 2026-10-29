package com.shifa.auth.infrastructure

import com.shifa.auth.domain.Role
import com.shifa.auth.domain.UserRole
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface UserRoleRepository : JpaRepository<UserRole, Long> {
    fun findByUserId(userId: Long): List<UserRole>
    fun findByUserIdAndRole(userId: Long, role: Role): Optional<UserRole>
    fun existsByUserIdAndRole(userId: Long, role: Role): Boolean
}
