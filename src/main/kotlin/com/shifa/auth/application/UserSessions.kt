package com.shifa.auth.application

import com.shifa.auth.domain.UserSession
import com.shifa.auth.infrastructure.UserSessionRepository
import java.util.*
import org.springframework.stereotype.Service

/**
 * The feature's door to UserSession records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [UserSessionRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class UserSessions(private val repo: UserSessionRepository) {
    fun save(entity: UserSession): UserSession = repo.save(entity)
}
