package com.shifa.repo

import com.shifa.domain.AdminProfile
import com.shifa.domain.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface AdminProfileRepository : JpaRepository<AdminProfile, Long> {
    fun findByUser(user: User): Optional<AdminProfile>
    fun findByUserId(userId: Long): Optional<AdminProfile>

    /** Admin user search: user IDs of admins whose first or last name contains q (JPQL). */
    @org.springframework.data.jpa.repository.Query(
        "SELECT a.user.id FROM AdminProfile a WHERE " +
        "LOWER(a.firstName) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(a.lastName) LIKE LOWER(CONCAT('%', :q, '%'))"
    )
    fun findUserIdsBySearch(@org.springframework.data.repository.query.Param("q") q: String): List<Long>
}
