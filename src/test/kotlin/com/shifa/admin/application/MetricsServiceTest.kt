package com.shifa.admin.application

import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
    fun `no timers is no rows`() {
        assertEquals(emptyList<Any>(), MetricsService.routesFrom(emptyList()))
    }
}
