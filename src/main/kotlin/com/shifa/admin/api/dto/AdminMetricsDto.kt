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
)

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
)
