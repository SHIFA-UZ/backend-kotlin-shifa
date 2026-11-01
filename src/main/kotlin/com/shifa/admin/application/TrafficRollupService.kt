package com.shifa.admin.application

import com.shifa.admin.api.dto.HttpRouteCountDto
import com.shifa.admin.api.dto.TrafficBucketDto
import com.shifa.admin.api.dto.TrafficRangeDto
import com.shifa.admin.infrastructure.TrafficRollupRepository
import com.shifa.shared.api.HttpTrafficWindow
import com.shifa.shared.api.LatencyHistogram
import com.shifa.shared.config.CacheConfig
import java.time.Duration
import java.time.Instant
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Saves the request filter's hourly counts and answers the health page's traffic views from them. */
@Service
class TrafficRollupService(private val rollups: TrafficRollupRepository) {
    @Transactional
    fun save(deltas: List<HttpTrafficWindow.HourDelta>) = deltas.forEach(rollups::add)

    @Transactional
    fun deleteExpired(now: Instant = Instant.now()) = rollups.deleteBefore(now.minus(RETENTION))

    @Transactional(readOnly = true)
    @Cacheable(CacheConfig.ADMIN_TRAFFIC_CACHE, key = "#range.name", sync = true)
    fun range(range: MetricsRange): TrafficRangeDto {
        val now = Instant.now()
        return summarize(
            range = range,
            rows = rollups.hoursFrom(Instant.ofEpochSecond(range.previousStart(now))),
            top4xx = rollups.top4xxFrom(Instant.ofEpochSecond(range.windowStart(now)), TOP_4XX),
            earliest = rollups.earliestHour(),
            now = now,
        )
    }

    companion object {
        private const val TOP_4XX = 4
        private const val P50 = 0.50
        private const val P95 = 0.95
        private const val P99 = 0.99

        /** Two 30 day views, the second one for the comparison, plus a little. */
        private val RETENTION = Duration.ofDays(62)
        private val STATUS_GROUPS = listOf("2xx", "304", "3xxOther", "401", "403", "4xxOther", "5xx")

        private fun merged(rows: List<TrafficRollupRepository.HourRow>) =
            LatencyHistogram().also { histogram -> rows.forEach { histogram.merge(it.latency) } }

        private fun LatencyHistogram.percentileOrNull(fraction: Double): Long? = if (total > 0) percentile(fraction) else null

        /**
         * Compares complete buckets only: the running one has barely started early in a bucket, so holding it against a
         * finished one would always read as a drop. "Previous" needs a saved hour older than its start, because the
         * oldest hour was probably recorded for only part of it.
         */
        internal fun summarize(
            range: MetricsRange,
            rows: List<TrafficRollupRepository.HourRow>,
            top4xx: List<HttpTrafficWindow.RouteCount>,
            earliest: Instant?,
            now: Instant,
        ): TrafficRangeDto {
            val windowStart = range.windowStart(now)
            val running = range.runningStart(now)
            val previousStart = range.previousStart(now)
            val window = rows.filter { it.hour.epochSecond >= windowStart }
            val complete = window.filter { it.hour.epochSecond < running }
            val hasPrevious = earliest != null && earliest.epochSecond < previousStart
            val previous = if (hasPrevious) {
                rows.filter { it.hour.epochSecond >= previousStart && it.hour.epochSecond < running - range.spanSeconds }
            } else {
                emptyList()
            }
            val latency = merged(window)
            val previousLatency = merged(previous)
            val peak = window.maxByOrNull { it.peakRps }
            val byBucket = window.groupBy { (it.hour.epochSecond - windowStart) / range.bucketSeconds }
            return TrafficRangeDto(
                range = range.param,
                bucketSeconds = range.bucketSeconds,
                from = Instant.ofEpochSecond(windowStart),
                collectingSince = earliest,
                requests = window.sumOf { it.requests },
                comparableRequests = complete.sumOf { it.requests },
                previousRequests = if (hasPrevious) previous.sumOf { it.requests } else null,
                p50Ms = latency.percentileOrNull(P50),
                p95Ms = latency.percentileOrNull(P95),
                p99Ms = latency.percentileOrNull(P99),
                comparableP95Ms = merged(complete).percentileOrNull(P95),
                previousP95Ms = previousLatency.percentileOrNull(P95),
                peakRps = peak?.peakRps ?: 0,
                peakAt = peak?.peakAt,
                byStatus = STATUS_GROUPS.associateWith { group -> window.sumOf { it.byStatus[group] ?: 0L } },
                buckets = (0 until range.buckets).map { i ->
                    val group = byBucket[i.toLong()].orEmpty()
                    TrafficBucketDto(
                        start = Instant.ofEpochSecond(windowStart + i * range.bucketSeconds),
                        requests = group.sumOf { it.requests },
                        p95Ms = merged(group).percentileOrNull(P95),
                        serverErrors = group.sumOf { it.byStatus["5xx"] ?: 0L },
                    )
                },
                top4xx = top4xx.map { HttpRouteCountDto(it.status, it.method, it.route, it.count) },
            )
        }
    }
}
