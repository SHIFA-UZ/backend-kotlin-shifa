package com.shifa.auth.infrastructure

import com.shifa.auth.domain.AccountDeleteChallenge
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID

interface AccountDeleteChallengeRepository : JpaRepository<AccountDeleteChallenge, UUID> {
    /** Atomic increment: concurrent failed attempts cannot lose updates. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update AccountDeleteChallenge c set c.attemptCount = c.attemptCount + 1 where c.challengeId = :challengeId")
    fun incrementAttemptCount(@org.springframework.data.repository.query.Param("challengeId") challengeId: java.util.UUID): Int

    fun findByChallengeId(challengeId: UUID): Optional<AccountDeleteChallenge>

    @Query(
        """
        SELECT c FROM AccountDeleteChallenge c
        WHERE c.user.id = :userId
          AND c.used = false
          AND c.expiresAt > :now
        ORDER BY c.createdAt DESC
        """
    )
    fun findActiveForUser(@Param("userId") userId: Long, @Param("now") now: OffsetDateTime): List<AccountDeleteChallenge>
}

