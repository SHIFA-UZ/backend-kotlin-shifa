package com.shifa.repo

import com.shifa.domain.UserSession
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.*

@Repository
interface UserSessionRepository : JpaRepository<UserSession, Long> {
    fun findByTokenJti(tokenJti: String): Optional<UserSession>
    
    @Query("SELECT s FROM UserSession s WHERE s.user.id = :userId AND s.revoked = false AND s.expiresAt > :now")
    fun findActiveSessionsByUserId(@Param("userId") userId: Long, @Param("now") now: OffsetDateTime): List<UserSession>
    
    @Query("SELECT s FROM UserSession s WHERE s.user.id = :userId ORDER BY s.createdAt DESC")
    fun findByUserIdOrderByCreatedAtDesc(userId: Long): List<UserSession>
    
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE UserSession s SET s.revoked = true, s.revokedAt = :now WHERE s.user.id = :userId AND s.revoked = false")
    fun revokeAllUserSessions(@Param("userId") userId: Long, @Param("now") now: OffsetDateTime): Int
}
