package com.shifa.service

import com.shifa.config.AiRateLimitProperties
import com.shifa.domain.AiUsageCounter
import com.shifa.domain.Role
import com.shifa.repo.AiUsageCounterRepository
import io.github.bucket4j.Bucket
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

sealed class AiRateLimitOutcome {
    /** Request may proceed; counter was incremented. */
    data class Allowed(
        val dailyLimit: Int,
        val remainingAfter: Int,
        val resetAtEpochSeconds: Long
    ) : AiRateLimitOutcome()

    /** Request blocked; counter was not incremented. */
    data class Denied(
        val dailyLimit: Int,
        val remaining: Int,
        val resetAtEpochSeconds: Long,
        val reason: DenyReason
    ) : AiRateLimitOutcome()
}

enum class DenyReason {
    DAILY_QUOTA,
    BURST
}

@Service
class AiRateLimitService(
    private val props: AiRateLimitProperties,
    private val repo: AiUsageCounterRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val burstBuckets = ConcurrentHashMap<Long, Bucket>()

    private fun createBucket(requestsPerMin: Int): Bucket {
        val n = requestsPerMin.toLong().coerceAtLeast(1L)
        return Bucket.builder()
            .addLimit { it.capacity(n).refillGreedy(n, java.time.Duration.ofMinutes(1)) }
            .build()
    }

    private fun burstBucketFor(userId: Long, requestsPerMin: Int): Bucket =
        burstBuckets.computeIfAbsent(userId) { createBucket(requestsPerMin) }

    private fun usageZone(): ZoneId = ZoneId.of(props.timezone)

    private fun endOfUsageDayEpochSeconds(today: LocalDate): Long =
        ZonedDateTime.of(today.plusDays(1), LocalTime.MIDNIGHT, usageZone())
            .toInstant()
            .epochSecond

    private fun dailyLimitFor(role: Role): Int = when (role) {
        Role.PATIENT -> props.patientDailyRequests
        Role.DOCTOR -> props.doctorDailyRequests
        else -> Int.MAX_VALUE
    }

    private fun burstPerMinFor(role: Role): Int = when (role) {
        Role.PATIENT -> props.patientBurstPerMin
        Role.DOCTOR -> props.doctorBurstPerMin
        else -> Int.MAX_VALUE
    }

    /**
     * Enforce daily (Postgres) + per-minute burst (in-process) limits and increment the daily counter on success.
     */
    @Transactional
    fun tryConsume(userId: Long, role: Role): AiRateLimitOutcome {
        if (!props.enabled) {
            return AiRateLimitOutcome.Allowed(
                dailyLimit = Int.MAX_VALUE,
                remainingAfter = Int.MAX_VALUE,
                resetAtEpochSeconds = FAR_FUTURE_EPOCH_SECONDS
            )
        }
        if (role != Role.DOCTOR && role != Role.PATIENT) {
            return AiRateLimitOutcome.Allowed(
                dailyLimit = Int.MAX_VALUE,
                remainingAfter = Int.MAX_VALUE,
                resetAtEpochSeconds = FAR_FUTURE_EPOCH_SECONDS
            )
        }

        val zone = usageZone()
        val today = LocalDate.now(zone)
        val dailyLimit = dailyLimitFor(role)
        val burstPerMin = burstPerMinFor(role)
        val resetAt = endOfUsageDayEpochSeconds(today)

        var row = repo.findByUserIdAndUsageDateForUpdate(userId, today)
        if (row == null) {
            try {
                row = repo.saveAndFlush(
                    AiUsageCounter(
                        userId = userId,
                        role = role,
                        usageDate = today,
                        requestCount = 0,
                        updatedAt = OffsetDateTime.now()
                    )
                )
            } catch (_: DataIntegrityViolationException) {
                row = repo.findByUserIdAndUsageDateForUpdate(userId, today)
            }
        }
        if (row == null) {
            log.error("ai_usage_counter: could not lock or create row for userId={} date={}", userId, today)
            return AiRateLimitOutcome.Denied(
                dailyLimit = dailyLimit,
                remaining = 0,
                resetAtEpochSeconds = resetAt,
                reason = DenyReason.DAILY_QUOTA
            )
        }

        if (row.requestCount >= dailyLimit) {
            return AiRateLimitOutcome.Denied(
                dailyLimit = dailyLimit,
                remaining = 0,
                resetAtEpochSeconds = resetAt,
                reason = DenyReason.DAILY_QUOTA
            )
        }

        val bucket = burstBucketFor(userId, burstPerMin)
        if (!bucket.tryConsume(1)) {
            return AiRateLimitOutcome.Denied(
                dailyLimit = dailyLimit,
                remaining = (dailyLimit - row.requestCount).coerceAtLeast(0),
                resetAtEpochSeconds = resetAt,
                reason = DenyReason.BURST
            )
        }

        row.requestCount += 1
        row.updatedAt = OffsetDateTime.now()
        repo.save(row)

        val remainingAfter = (dailyLimit - row.requestCount).coerceAtLeast(0)
        return AiRateLimitOutcome.Allowed(
            dailyLimit = dailyLimit,
            remainingAfter = remainingAfter,
            resetAtEpochSeconds = resetAt
        )
    }

    companion object {
        private val FAR_FUTURE_EPOCH_SECONDS: Long =
            ZonedDateTime.of(LocalDate.of(2099, 1, 1), LocalTime.MIDNIGHT, ZoneOffset.UTC)
                .toInstant().epochSecond
    }
}
