package com.shifa.shared.api

import java.time.Instant
import org.springframework.stereotype.Component

/**
 * API traffic per hour for the admin health page: 48 hourly buckets (the last 24 hours and the 24 before them) and
 * latency per route since start. Fed by [RequestTimingFilter], so it covers `/api` only and holds route templates,
 * never raw paths. In memory: a deploy resets it, so what must outlive one is handed over through [drain].
 */
@Component
class HttpTrafficWindow {
    data class HourRow(val hour: Instant, val requests: Long, val p95Ms: Long?, val serverErrors: Long)
    data class RouteCount(val status: Int, val method: String, val route: String, val count: Long)
    data class RoutePercentiles(val p95Ms: Long, val p99Ms: Long)

    /** What happened in one hour since the last [drain]. */
    data class HourDelta(
        val hour: Instant,
        val requests: Long,
        val byStatus: Map<String, Long>,
        val latency: LatencyHistogram,
        val peakRps: Long,
        val peakAt: Instant,
        val clientErrors: List<RouteCount>,
    )

    data class Snapshot(
        val requests: Long,
        val previousRequests: Long?,
        val p50Ms: Long,
        val p95Ms: Long,
        val p99Ms: Long,
        val previousP95Ms: Long?,
        val peakRps: Long,
        val peakAt: Instant?,
        val byStatus: Map<String, Long>,
        val hourly: List<HourRow>,
        val top4xx: List<RouteCount>,
    )

    private class Bucket(val hour: Long) {
        var requests = 0L
        var serverErrors = 0L
        val latency = LatencyHistogram()
        val byStatus = HashMap<String, Long>()
        val clientErrors = HashMap<Triple<Int, String, String>, Long>()
        var currentSecond = 0L
        var currentSecondCount = 0L
        var peakRps = 0L
        var peakSecond = 0L

        fun add(method: String, route: String, status: Int, ms: Long, second: Long) {
            requests++
            latency.add(ms)
            byStatus.merge(statusGroup(status), 1L, Long::plus)
            if (status >= SERVER_ERROR) serverErrors++
            if (status in CLIENT_ERROR until SERVER_ERROR) clientErrors.merge(Triple(status, method, route), 1L, Long::plus)
            if (currentSecond != second) {
                currentSecond = second
                currentSecondCount = 0
            }
            currentSecondCount++
            if (currentSecondCount > peakRps) {
                peakRps = currentSecondCount
                peakSecond = second
            }
        }
    }

    private val slots = arrayOfNulls<Bucket>(HOURS * 2)
    private val routeLatency = HashMap<String, LatencyHistogram>()
    private var undrained = HashMap<Long, Bucket>()
    private var firstHour: Long? = null

    // ponytail: one lock for all requests; it guards a few array writes, so split it only if it ever shows up in a profile.
    @Synchronized
    fun record(rawMethod: String, route: String, status: Int, ms: Long, at: Instant = Instant.now()) {
        val method = if (rawMethod in KNOWN_METHODS) rawMethod else OTHER_METHOD // a client can send any token here
        val second = at.epochSecond
        val hour = second / SECONDS_PER_HOUR
        if (firstHour == null) firstHour = hour
        val slot = (hour % slots.size).toInt()
        val bucket = slots[slot]?.takeIf { it.hour == hour } ?: Bucket(hour).also { slots[slot] = it }
        bucket.add(method, route, status, ms, second)
        undrained.getOrPut(hour) { Bucket(hour) }.add(method, route, status, ms, second)
        routeLatency.getOrPut("$method $route") { LatencyHistogram() }.add(ms)
    }

    /**
     * Null until the window has seen a request. The window is the running hour plus the 23 before it. "Previous" is
     * those 23 complete hours one day earlier, so compare it with the complete hours of [Snapshot.hourly], not with
     * [Snapshot.requests]; it stays null until all of those hours were watched from start to end.
     */
    @Synchronized
    fun snapshot(now: Instant = Instant.now()): Snapshot? {
        val hour = now.epochSecond / SECONDS_PER_HOUR
        val current = (hour - HOURS + 1..hour).map { h -> h to bucketAt(h) }
        val buckets = current.mapNotNull { it.second }
        if (buckets.isEmpty()) return null

        val latency = LatencyHistogram().also { merged -> buckets.forEach { merged.merge(it.latency) } }
        val hasPrevious = (firstHour ?: hour) <= hour - HOURS * 2
        val previous = if (hasPrevious) (hour - HOURS * 2 + 1 until hour - HOURS).mapNotNull(::bucketAt) else emptyList()
        val previousLatency = LatencyHistogram().also { merged -> previous.forEach { merged.merge(it.latency) } }
        val peak = buckets.maxBy { it.peakRps }
        val byStatus = STATUS_GROUPS.associateWith { group -> buckets.sumOf { it.byStatus[group] ?: 0L } }
        val clientErrors = HashMap<Triple<Int, String, String>, Long>()
        buckets.forEach { b -> b.clientErrors.forEach { (key, count) -> clientErrors.merge(key, count, Long::plus) } }

        return Snapshot(
            requests = buckets.sumOf { it.requests },
            previousRequests = if (hasPrevious) previous.sumOf { it.requests } else null,
            p50Ms = latency.percentile(P50),
            p95Ms = latency.percentile(P95),
            p99Ms = latency.percentile(P99),
            previousP95Ms = if (previousLatency.total > 0) previousLatency.percentile(P95) else null,
            peakRps = peak.peakRps,
            peakAt = Instant.ofEpochSecond(peak.peakSecond),
            byStatus = byStatus,
            hourly = current.map { (h, b) ->
                HourRow(Instant.ofEpochSecond(h * SECONDS_PER_HOUR), b?.requests ?: 0, b?.latency?.percentile(P95), b?.serverErrors ?: 0)
            },
            top4xx = clientErrors.entries
                .sortedByDescending { it.value }
                .take(TOP_4XX)
                .map { (key, count) -> RouteCount(key.first, key.second, key.third, count) },
        )
    }

    /** Everything recorded since the last call, and forgets it. If saving fails, hand it back through [restore]. */
    @Synchronized
    fun drain(): List<HourDelta> {
        val drained = undrained
        undrained = HashMap()
        // A drain can fall inside a busy second: the next bucket goes on counting that second, or its peak would split in two.
        drained.values.maxByOrNull { it.hour }?.let { last ->
            undrained[last.hour] = Bucket(last.hour).apply {
                currentSecond = last.currentSecond
                currentSecondCount = last.currentSecondCount
            }
        }
        return drained.values.filter { it.requests > 0 }.sortedBy { it.hour }.map { b ->
            HourDelta(
                hour = Instant.ofEpochSecond(b.hour * SECONDS_PER_HOUR),
                requests = b.requests,
                byStatus = STATUS_GROUPS.associateWith { b.byStatus[it] ?: 0L },
                latency = b.latency,
                peakRps = b.peakRps,
                peakAt = Instant.ofEpochSecond(b.peakSecond),
                clientErrors = b.clientErrors.map { (key, count) -> RouteCount(key.first, key.second, key.third, count) },
            )
        }
    }

    @Synchronized
    fun restore(deltas: List<HourDelta>) {
        deltas.forEach { d ->
            val b = undrained.getOrPut(d.hour.epochSecond / SECONDS_PER_HOUR) { Bucket(d.hour.epochSecond / SECONDS_PER_HOUR) }
            b.requests += d.requests
            b.latency.merge(d.latency)
            d.byStatus.forEach { (group, count) -> b.byStatus.merge(group, count, Long::plus) }
            d.clientErrors.forEach { b.clientErrors.merge(Triple(it.status, it.method, it.route), it.count, Long::plus) }
            if (d.peakRps > b.peakRps) {
                b.peakRps = d.peakRps
                b.peakSecond = d.peakAt.epochSecond
            }
        }
    }

    /** Percentiles of one route since start, null for a route the filter has not seen. */
    @Synchronized
    fun routePercentiles(method: String, route: String): RoutePercentiles? =
        routeLatency["$method $route"]?.let { RoutePercentiles(it.percentile(P95), it.percentile(P99)) }

    private fun bucketAt(hour: Long): Bucket? = slots[(hour % slots.size).toInt()]?.takeIf { it.hour == hour }

    private companion object {
        const val HOURS = 24
        const val SECONDS_PER_HOUR = 3600L
        const val TOP_4XX = 4
        const val SUCCESS = 200
        const val REDIRECT = 300
        const val NOT_MODIFIED = 304
        const val CLIENT_ERROR = 400
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val SERVER_ERROR = 500
        const val P50 = 0.50
        const val P95 = 0.95
        const val P99 = 0.99
        const val OTHER_METHOD = "OTHER"
        val KNOWN_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE")
        val STATUS_GROUPS = listOf("2xx", "304", "3xxOther", "401", "403", "4xxOther", "5xx")

        fun statusGroup(status: Int): String = when {
            status >= SERVER_ERROR -> "5xx"
            status == UNAUTHORIZED -> "401"
            status == FORBIDDEN -> "403"
            status >= CLIENT_ERROR -> "4xxOther"
            status == NOT_MODIFIED -> "304"
            status in SUCCESS until REDIRECT -> "2xx"
            else -> "3xxOther"
        }
    }
}
