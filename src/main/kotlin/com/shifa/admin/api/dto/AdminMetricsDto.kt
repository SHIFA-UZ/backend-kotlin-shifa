package com.shifa.admin.api.dto

import java.time.Instant

/** `GET /api/admin/metrics`: one read of the running service, its JVM, its database and its traffic counters. */
data class AdminMetricsDto(
    val generatedAt: Instant,
    val app: AppMetricsDto,
    val jvm: JvmMetricsDto,
    val database: DatabaseMetricsDto,
    val domain: DomainMetricsDto,
    val http: HttpMetricsDto,
    val jobs: List<JobMetricsDto>? = null,
    val ai24h: List<AiProviderMetricsDto>? = null,
    val aiFallbacks24h: Long? = null,
    val paymentsLast24h: List<PaymentCountDto>? = null,
)

data class AppMetricsDto(
    val commit: String,
    val springBoot: String,
    val javaVersion: String,
    val startedAt: Instant,
    val uptimeSeconds: Long,
)

data class JvmMetricsDto(
    val heapUsedMb: Long,
    val heapMaxMb: Long,
    val nonHeapUsedMb: Long,
    val threads: Long,
)

data class DatabaseMetricsDto(
    val sizeBytes: Long,
    val postgresVersion: String,
    val schemaVersion: Int?,
    val lastMigration: String?,
    val migratedAt: Instant?,
    val serverConnections: ConnectionStatesDto,
    val pool: PoolMetricsDto,
    val tables: List<TableSizeDto>,
    val cacheHitRatio: Double? = null,
    val deadTupleRatio: DeadTupleRatioDto? = null,
    val unusedIndexes: UnusedIndexesDto? = null,
)

data class DeadTupleRatioDto(val table: String, val ratio: Double)

data class UnusedIndexesDto(val count: Long, val bytes: Long)

data class ConnectionStatesDto(val active: Long, val idle: Long, val idleInTransaction: Long)

data class PoolMetricsDto(val active: Long, val idle: Long, val pending: Long, val max: Long)

/** [estimatedRows] is PostgreSQL's `n_live_tup` estimate, not a count. */
data class TableSizeDto(val table: String, val estimatedRows: Long, val bytes: Long)

data class DomainMetricsDto(
    val usersByRole: List<CountByDto>,
    val appointmentsByStatus: List<AppointmentCountDto>,
    val paymentsByStatus: List<PaymentCountDto>,
    val notificationsTotal: Long,
    val notificationsUnread: Long,
    val sessionsActive: Long,
    val appointmentsPerDay: List<DayCountDto>,
)

data class CountByDto(val key: String, val count: Long)

data class AppointmentCountDto(val status: String, val paymentStatus: String, val count: Long)

data class PaymentCountDto(val status: String, val gateway: String, val count: Long)

data class DayCountDto(val day: String, val count: Long)

/** Counters since the process started, from Micrometer's `http.server.requests` timer. */
data class HttpMetricsDto(
    val since: Instant,
    val requests: Long,
    val serverErrors: Long,
    val routes: List<RouteMetricsDto>,
    val last24h: HttpWindowDto? = null,
)

/** One view of `GET /api/admin/metrics/traffic`, from the saved hourly rollups: it survives a deploy and lags about a minute. */
data class TrafficRangeDto(
    /** 24h, 7d or 30d. */
    val range: String,
    val bucketSeconds: Long,
    val from: Instant,
    /** The oldest saved hour. Later than `from` while the rollups are younger than the range. */
    val collectingSince: Instant? = null,
    val requests: Long,
    /** `requests` without the running bucket, the number to hold against `previousRequests`. */
    val comparableRequests: Long,
    /** The same complete buckets one range earlier. Null until all of them were recorded from start to end. */
    val previousRequests: Long? = null,
    val p50Ms: Long? = null,
    val p95Ms: Long? = null,
    val p99Ms: Long? = null,
    /** `p95Ms` without the running bucket, the number to hold against `previousP95Ms`. */
    val comparableP95Ms: Long? = null,
    val previousP95Ms: Long? = null,
    val peakRps: Long,
    val peakAt: Instant? = null,
    /** Keys: 2xx, 304, 3xxOther, 401, 403, 4xxOther, 5xx. */
    val byStatus: Map<String, Long>,
    /** Oldest first, the last one still running: 24 of an hour, 28 of 6 hours, or 30 of a UTC day. */
    val buckets: List<TrafficBucketDto>,
    val top4xx: List<HttpRouteCountDto>,
)

data class TrafficBucketDto(val start: Instant, val requests: Long, val p95Ms: Long? = null, val serverErrors: Long)

/** `/api` traffic of the last 24 hours as far as this process has seen it: a deploy resets it. The page reads [TrafficRangeDto] instead. */
data class HttpWindowDto(
    val requests: Long,
    /** The 23 complete hours of this window, one day earlier: compare with `hourly` minus its running last row. Null until watched in full. */
    val previousRequests: Long? = null,
    val p50Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
    val previousP95Ms: Long? = null,
    val peakRps: Long,
    val peakAt: Instant? = null,
    /** Keys: 2xx, 304, 3xxOther, 401, 403, 4xxOther, 5xx. */
    val byStatus: Map<String, Long>,
    /** 24 rows, oldest first. */
    val hourly: List<HttpHourDto>,
    val top4xx: List<HttpRouteCountDto>,
)

data class HttpHourDto(val hour: Instant, val requests: Long, val p95Ms: Long? = null, val serverErrors: Long)

data class HttpRouteCountDto(val status: Int, val method: String, val route: String, val count: Long)

/** A scheduled job as ShedLock saw it. `nextRunAt` is the last start plus the job's interval. */
data class JobMetricsDto(val name: String, val lastRunAt: Instant, val nextRunAt: Instant? = null, val note: String? = null)

data class AiProviderMetricsDto(
    val provider: String,
    val use: String? = null,
    val calls: Long,
    val p95Ms: Long? = null,
    val errorRatio: Double,
    val tokensIn: Long,
    val tokensOut: Long,
)

data class RouteMetricsDto(
    val method: String,
    val route: String,
    val requests: Long,
    val serverErrors: Long,
    val clientErrors: Long,
    val averageMs: Long,
    /** Micrometer keeps the maximum over a rolling window of about two minutes, not since start. */
    val recentMaxMs: Long,
    /** Since start, from the `/api` request filter; null for paths it does not cover. */
    val p95Ms: Long? = null,
    val p99Ms: Long? = null,
)
