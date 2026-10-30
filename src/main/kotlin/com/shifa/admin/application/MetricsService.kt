package com.shifa.admin.application

import com.shifa.admin.api.dto.AdminMetricsDto
import com.shifa.admin.api.dto.AppMetricsDto
import com.shifa.admin.api.dto.AppointmentCountDto
import com.shifa.admin.api.dto.ConnectionStatesDto
import com.shifa.admin.api.dto.CountByDto
import com.shifa.admin.api.dto.DatabaseMetricsDto
import com.shifa.admin.api.dto.DayCountDto
import com.shifa.admin.api.dto.DomainMetricsDto
import com.shifa.admin.api.dto.HttpMetricsDto
import com.shifa.admin.api.dto.JvmMetricsDto
import com.shifa.admin.api.dto.PaymentCountDto
import com.shifa.admin.api.dto.PoolMetricsDto
import com.shifa.admin.api.dto.RouteMetricsDto
import com.shifa.admin.api.dto.TableSizeDto
import com.shifa.admin.infrastructure.MetricsQueries
import com.shifa.shared.config.CacheConfig
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.SpringBootVersion
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The numbers an operator watches: the running build, JVM memory and threads, the database and its pool, counts
 * of the business records, and the request counters Micrometer keeps since the process started. One read, no
 * patient data; the admin dashboard polls it.
 */
@Service
@Transactional(readOnly = true)
class MetricsService(
    private val queries: MetricsQueries,
    private val registry: MeterRegistry,
    @Value("\${info.commit:local}") private val commit: String,
) {
    /** Cached for a few seconds ([CacheConfig.ADMIN_METRICS_CACHE]) so several open dashboards share one read. */
    @Cacheable(CacheConfig.ADMIN_METRICS_CACHE, sync = true)
    fun snapshot(): AdminMetricsDto {
        val now = Instant.now()
        val startedAt = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().startTime)
        return AdminMetricsDto(
            generatedAt = now,
            app = AppMetricsDto(
                commit = commit,
                springBoot = SpringBootVersion.getVersion() ?: "unknown",
                javaVersion = System.getProperty("java.version") ?: "unknown",
                startedAt = startedAt,
                uptimeSeconds = now.epochSecond - startedAt.epochSecond,
            ),
            jvm = jvm(),
            database = database(),
            domain = domain(),
            http = http(startedAt),
        )
    }

    private fun gauge(name: String, vararg tags: String): Long =
        registry.find(name).tags(*tags).gauges().sumOf { it.value() }.toLong()

    private fun jvm() = JvmMetricsDto(
        heapUsedMb = gauge("jvm.memory.used", "area", "heap") / MB,
        heapMaxMb = gauge("jvm.memory.max", "area", "heap") / MB,
        nonHeapUsedMb = gauge("jvm.memory.used", "area", "nonheap") / MB,
        threads = gauge("jvm.threads.live"),
    )

    private fun database(): DatabaseMetricsDto {
        val migration = queries.lastMigration()
        val connections = queries.connections()
        return DatabaseMetricsDto(
            sizeBytes = queries.databaseSizeBytes(),
            postgresVersion = queries.postgresVersion(),
            schemaVersion = migration?.version,
            lastMigration = migration?.description,
            migratedAt = migration?.installedOn?.toInstant(),
            serverConnections = ConnectionStatesDto(connections.active, connections.idle, connections.idleInTransaction),
            pool = PoolMetricsDto(
                active = gauge("hikaricp.connections.active"),
                idle = gauge("hikaricp.connections.idle"),
                pending = gauge("hikaricp.connections.pending"),
                max = gauge("hikaricp.connections.max"),
            ),
            tables = queries.largestTables(TABLES).map { TableSizeDto(it.table, it.estimatedRows, it.bytes) },
        )
    }

    private fun domain(): DomainMetricsDto {
        val (total, unread) = queries.notificationCounts()
        return DomainMetricsDto(
            usersByRole = queries.usersByRole().map { CountByDto(it.a, it.b) },
            appointmentsByStatus = queries.appointmentsByStatus().map { AppointmentCountDto(it.a, it.b, it.c) },
            paymentsByStatus = queries.paymentsByStatus().map { PaymentCountDto(it.a, it.b, it.c) },
            notificationsTotal = total,
            notificationsUnread = unread,
            sessionsActive = queries.activeSessions(),
            appointmentsPerDay = queries.appointmentsPerDay(DAYS).map { DayCountDto(it.a, it.b) },
        )
    }

    private fun http(since: Instant): HttpMetricsDto {
        val routes = routesFrom(registry.find("http.server.requests").timers())
        return HttpMetricsDto(
            since = since,
            requests = routes.sumOf { it.requests },
            serverErrors = routes.sumOf { it.serverErrors },
            routes = routes,
        )
    }

    companion object {
        private const val MB = 1024L * 1024L
        private const val TABLES = 15
        private const val DAYS = 30
        private const val SERVER_ERROR = 500
        private const val CLIENT_ERROR = 400

        /** One row per method and route template, every status folded in, busiest first. */
        internal fun routesFrom(timers: Collection<Timer>): List<RouteMetricsDto> =
            timers
                .groupBy { (it.id.getTag("method") ?: "?") to (it.id.getTag("uri") ?: "?") }
                .map { (key, group) ->
                    val count = group.sumOf { it.count() }
                    val totalMs = group.sumOf { it.totalTime(TimeUnit.MILLISECONDS) }
                    RouteMetricsDto(
                        method = key.first,
                        route = key.second,
                        requests = count,
                        serverErrors = group.filter { statusOf(it) >= SERVER_ERROR }.sumOf { it.count() },
                        clientErrors = group.filter { statusOf(it) in CLIENT_ERROR until SERVER_ERROR }.sumOf { it.count() },
                        averageMs = if (count == 0L) 0 else (totalMs / count).toLong(),
                        recentMaxMs = group.maxOf { it.max(TimeUnit.MILLISECONDS) }.toLong(),
                    )
                }
                .sortedByDescending { it.requests }

        private fun statusOf(timer: Timer): Int = timer.id.getTag("status")?.toIntOrNull() ?: 0
    }
}
