package com.shifa.repo

import com.shifa.domain.UserSession
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
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
    
    @Modifying
    @Query("UPDATE UserSession s SET s.revoked = true, s.revokedAt = :now WHERE s.user.id = :userId AND s.revoked = false")
    fun revokeAllUserSessions(@Param("userId") userId: Long, @Param("now") now: OffsetDateTime): Int

    /** Delete sessions past their JWT expiry (table hygiene for long-lived doctor/patient tokens). */
    @Modifying
    @Query("DELETE FROM UserSession s WHERE s.expiresAt < :cutoff")
    fun deleteExpired(@Param("cutoff") cutoff: OffsetDateTime): Int

    /** Delete revoked sessions whose revoke timestamp is older than [cutoff] (keeps history briefly). */
    @Modifying
    @Query("DELETE FROM UserSession s WHERE s.revoked = true AND s.revokedAt IS NOT NULL AND s.revokedAt < :cutoff")
    fun deleteRevokedBefore(@Param("cutoff") cutoff: OffsetDateTime): Int
}
