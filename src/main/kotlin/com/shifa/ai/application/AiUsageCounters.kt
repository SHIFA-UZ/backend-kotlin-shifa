package com.shifa.ai.application

import com.shifa.ai.infrastructure.AiUsageCounterRepository
import com.shifa.auth.domain.Role
import java.time.LocalDate
import org.springframework.stereotype.Service

/**
 * The feature's door to AiUsageCounter records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [AiUsageCounterRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class AiUsageCounters(private val repo: AiUsageCounterRepository) {
    fun sumRequestCountByUserAndUsageDateBetween(userId: Long, role: Role, from: LocalDate, toInclusive: LocalDate): Long = repo.sumRequestCountByUserAndUsageDateBetween(userId, role, from, toInclusive)
    fun sumRequestCountGroupedByUsageDate(userId: Long, role: Role, from: LocalDate, toInclusive: LocalDate): List<Array<Any>> = repo.sumRequestCountGroupedByUsageDate(userId, role, from, toInclusive)
    fun sumRequestCountAllTimeForUser(userId: Long, role: Role): Long = repo.sumRequestCountAllTimeForUser(userId, role)
    fun findMaxUsageDate(userId: Long, role: Role): LocalDate? = repo.findMaxUsageDate(userId, role)
}
