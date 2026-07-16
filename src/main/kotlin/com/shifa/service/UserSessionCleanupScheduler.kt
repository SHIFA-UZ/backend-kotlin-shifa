package com.shifa.service

import com.shifa.repo.UserSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Purges expired and long-revoked [user_sessions] rows so the table does not grow unbounded
 * with 30-day doctor / 365-day patient JWTs.
 */
@Component
class UserSessionCleanupScheduler(
    private val userSessionRepository: UserSessionRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedRate = 3_600_000)
    @Transactional
    fun cleanupExpiredSessions() {
        val now = OffsetDateTime.now()
        val expiredDeleted = userSessionRepository.deleteExpired(now)
        val revokedDeleted = userSessionRepository.deleteRevokedBefore(now.minusDays(7))
        if (expiredDeleted > 0 || revokedDeleted > 0) {
            log.info(
                "User session cleanup: deleted {} expired, {} revoked older than 7 days",
                expiredDeleted,
                revokedDeleted,
            )
        }
    }
}
