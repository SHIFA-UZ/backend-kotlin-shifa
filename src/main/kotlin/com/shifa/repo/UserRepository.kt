package com.shifa.repo

import com.shifa.domain.Role
import com.shifa.domain.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.*

interface UserRepository : JpaRepository<User, Long>, UserRepositoryCustom {
    fun findByEmail(email: String): Optional<User>

    /** Case-insensitive match for login / registration (email column may be mixed case from legacy data). */
    fun findByEmailIgnoreCase(email: String): Optional<User>
    fun findByPhone(phone: String): Optional<User>
    fun findByUsername(username: String): Optional<User>
    fun findByRole(role: Role, pageable: Pageable): Page<User>
    fun findByEnabled(enabled: Boolean, pageable: Pageable): Page<User>
    fun findByRoleAndEnabled(role: Role, enabled: Boolean, pageable: Pageable): Page<User>
    fun countByRole(role: Role): Long
    fun countByRoleAndEnabled(role: Role, enabled: Boolean): Long

    @Query("SELECT COUNT(u) FROM User u WHERE u.role = :role AND u.lastLoginAt IS NULL")
    fun countByRoleAndLastLoginAtIsNull(@Param("role") role: Role): Long

    @Query("SELECT COUNT(u) FROM User u WHERE u.role = :role AND u.lastLoginAt IS NOT NULL")
    fun countByRoleAndLastLoginAtIsNotNull(@Param("role") role: Role): Long

    @Query("SELECT u FROM User u WHERE u.id IN :ids")
    fun findByIdIn(@Param("ids") ids: Collection<Long>, pageable: Pageable): Page<User>

    @Query(
        """SELECT u.id FROM User u
           WHERE u.role = com.shifa.domain.Role.PATIENT
           AND u.id NOT IN (
               SELECT p.user.id FROM PatientProfile p
               WHERE p.user IS NOT NULL
               AND p.fcmToken IS NOT NULL AND TRIM(p.fcmToken) <> ''
           )"""
    )
    fun findPatientUserIdsWithoutDeviceRegistered(): List<Long>

    @Query(
        """SELECT u.id FROM User u
           WHERE u.role = com.shifa.domain.Role.DOCTOR
           AND u.id NOT IN (
               SELECT d.user.id FROM DoctorProfile d
               WHERE d.fcmToken IS NOT NULL AND TRIM(d.fcmToken) <> ''
           )"""
    )
    fun findDoctorUserIdsWithoutDeviceRegistered(): List<Long>
    
    // Search users by query string (email or phone) for specific role, excluding a user ID
    @Query(
        "SELECT u FROM User u WHERE u.id != :excludeUserId AND u.role = :role AND " +
        "(LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(u.phone) LIKE LOWER(CONCAT('%', :query, '%')))"
    )
    fun searchByRoleAndQuery(
        excludeUserId: Long,
        role: Role,
        query: String
    ): List<User>

    /** Admin user search: user IDs matching phone, email or username (JPQL, no native). Optional role/enabled. */
    @Query(
        "SELECT u.id FROM User u WHERE " +
        "(LOWER(COALESCE(u.phone, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
        " LOWER(COALESCE(u.email, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
        " LOWER(COALESCE(u.username, '')) LIKE LOWER(CONCAT('%', :q, '%'))) " +
        "AND (:role IS NULL OR u.role = :role) AND (:enabled IS NULL OR u.enabled = :enabled)"
    )
    fun findUserIdsBySearch(
        @Param("q") q: String,
        @Param("role") role: Role?,
        @Param("enabled") enabled: Boolean?
    ): List<Long>

    fun findByPhoneOriginalHashAndAccountStatus(
        phoneOriginalHash: String,
        accountStatus: User.AccountStatus = User.AccountStatus.DELETED
    ): Optional<User>

    fun findByEmailOriginalHashAndAccountStatus(
        emailOriginalHash: String,
        accountStatus: User.AccountStatus = User.AccountStatus.DELETED
    ): Optional<User>
}
