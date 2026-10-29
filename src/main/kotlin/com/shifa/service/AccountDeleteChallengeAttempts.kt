package com.shifa.service

import com.shifa.repo.AccountDeleteChallengeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Counts a failed deletion-confirmation attempt in its own transaction, so the 5-attempt limit holds even though the caller rolls back. */
@Service
class AccountDeleteChallengeAttempts(private val challenges: AccountDeleteChallengeRepository) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordFailure(challengeId: UUID) {
        challenges.findByChallengeId(challengeId).ifPresent {
            it.attemptCount += 1
            challenges.save(it)
        }
    }
}
