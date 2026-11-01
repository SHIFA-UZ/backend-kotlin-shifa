package com.shifa.admin.application

import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MetricsServiceTest {
    private val registry = SimpleMeterRegistry()

    private fun record(method: String, uri: String, status: String, vararg ms: Long) {
        val timer = Timer.builder("http.server.requests").tags("method", method, "uri", uri, "status", status).register(registry)
        ms.forEach { timer.record(it, TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `routes fold every status of one method and template into one row, busiest first`() {
        record("GET", "/api/patients/me/profile", "200", 10, 30)
        record("GET", "/api/patients/me/profile", "500", 200)
        record("GET", "/api/patients/me/profile", "401", 1, 1, 1)
        record("POST", "/api/auth/login", "200", 80)

        val rows = MetricsService.routesFrom(registry.find("http.server.requests").timers())

        assertEquals(listOf("GET /api/patients/me/profile", "POST /api/auth/login"), rows.map { "${it.method} ${it.route}" })
        val profile = rows.first()
        assertEquals(6, profile.requests)
        assertEquals(1, profile.serverErrors)
        assertEquals(3, profile.clientErrors)
        assertEquals((10 + 30 + 200 + 3) / 6L, profile.averageMs)
        assertEquals(200, profile.recentMaxMs)
    }

    @Test
    fun `appointment buckets fill empty ones, and the comparison leaves out the running bucket and its counterpart`() {
        val now = Instant.parse("2026-11-20T12:30:00Z")
        fun day(date: String) = Instant.parse("${date}T00:00:00Z").epochSecond
        val a = MetricsService.appointmentActivityOf(
            MetricsRange.MONTH,
            mapOf(
                day("2026-09-22") to 2L, // first day of the previous range
                day("2026-09-21") to 50L, // before it
                day("2026-10-21") to 40L, // the running day's counterpart
                day("2026-10-22") to 3L, // first day of this range
                day("2026-11-19") to 4L,
                day("2026-11-20") to 5L, // running
            ),
            now,
        )
        assertEquals(30, a.buckets.size)
        assertEquals(Instant.parse("2026-10-22T00:00:00Z"), a.from)
        assertEquals(12, a.total)
        assertEquals(7, a.comparableTotal)
        assertEquals(2, a.previousTotal)
        assertEquals(listOf(3L, 0L), a.buckets.take(2).map { it.count })
        assertEquals(Instant.parse("2026-11-20T00:00:00Z"), a.buckets.last().start)
        assertEquals(5, a.buckets.last().count)
        assertEquals(listOf(24, 28, 30), MetricsRange.entries.map { MetricsService.appointmentActivityOf(it, emptyMap(), now).buckets.size })
    }

    @Test
    fun `no timers is no rows`() {
        assertEquals(emptyList<Any>(), MetricsService.routesFrom(emptyList()))
    }
}
