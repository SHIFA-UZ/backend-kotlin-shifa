package com.shifa.auth.application

import com.shifa.auth.domain.AccountDeleteChallenge
import com.shifa.auth.infrastructure.AccountDeleteChallengeRepository
import java.util.Optional
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * The feature's door to AccountDeleteChallenge records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [AccountDeleteChallengeRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class AccountDeleteChallenges(private val repo: AccountDeleteChallengeRepository) {
    fun findByChallengeId(challengeId: UUID): Optional<AccountDeleteChallenge> = repo.findByChallengeId(challengeId)
    fun save(entity: AccountDeleteChallenge): AccountDeleteChallenge = repo.save(entity)
}
