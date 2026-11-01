package com.shifa.shared.api

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HttpTrafficWindowTest {
    private val noon = Instant.parse("2026-11-01T12:30:00Z")
    private fun hoursAgo(hours: Long) = noon.minusSeconds(hours * 3600)

    @Test
    fun `no request is no snapshot`() {
        assertNull(HttpTrafficWindow().snapshot(noon))
    }

    @Test
    fun `every status lands in one group, 24 hours oldest first, quiet hours are zero`() {
        val window = HttpTrafficWindow()
        listOf(200, 204, 304, 302, 401, 403, 404, 422, 500, 503).forEach { window.record("GET", "/api/x", it, 10, noon) }
        window.record("GET", "/api/x", 200, 10, hoursAgo(23))

        val s = window.snapshot(noon)!!

        assertEquals(11, s.requests)
        assertEquals(mapOf("2xx" to 3L, "304" to 1L, "3xxOther" to 1L, "401" to 1L, "403" to 1L, "4xxOther" to 2L, "5xx" to 2L), s.byStatus)
        assertEquals(s.requests, s.byStatus.values.sum())
        assertEquals(24, s.hourly.size)
        assertEquals(Instant.parse("2026-10-31T13:00:00Z"), s.hourly.first().hour)
        assertEquals(1, s.hourly.first().requests)
        assertEquals(Instant.parse("2026-11-01T12:00:00Z"), s.hourly.last().hour)
        assertEquals(10, s.hourly.last().requests)
        assertEquals(2, s.hourly.last().serverErrors)
        assertEquals(0, s.hourly[5].requests)
        assertNull(s.hourly[5].p95Ms)
    }

    @Test
    fun `hours older than the window drop out and their slot is reused`() {
        val window = HttpTrafficWindow()
        window.record("GET", "/api/x", 200, 10, hoursAgo(48))
        window.record("GET", "/api/x", 200, 10, noon)

        val s = window.snapshot(noon)!!

        assertEquals(1, s.requests)
        assertEquals(0, s.previousRequests)
    }

    @Test
    fun `previous window stays unknown until 24 earlier hours exist`() {
        val young = HttpTrafficWindow()
        young.record("GET", "/api/x", 200, 10, hoursAgo(30))
        young.record("GET", "/api/x", 200, 10, noon)
        assertNull(young.snapshot(noon)!!.previousRequests)

        val barely = HttpTrafficWindow()
        barely.record("GET", "/api/x", 200, 10, hoursAgo(47)) // the first hour was only partly watched
        barely.record("GET", "/api/x", 200, 10, noon)
        assertNull(barely.snapshot(noon)!!.previousRequests)

        val old = HttpTrafficWindow()
        old.record("GET", "/api/x", 200, 10, hoursAgo(49))
        old.record("GET", "/api/x", 200, 100, hoursAgo(47))
        old.record("GET", "/api/x", 200, 10, noon)
        val s = old.snapshot(noon)!!
        assertEquals(1, s.previousRequests)
        assertEquals(100, s.previousP95Ms)
    }

    @Test
    fun `previous counts the complete hours a day earlier, not the running hour's counterpart`() {
        val window = HttpTrafficWindow()
        window.record("GET", "/api/x", 200, 10, hoursAgo(49))
        repeat(3) { window.record("GET", "/api/x", 200, 10, hoursAgo(25)) } // counterpart of the last complete hour
        repeat(10) { window.record("GET", "/api/x", 200, 9000, hoursAgo(24)) } // counterpart of the running hour
        window.record("GET", "/api/x", 200, 10, noon)

        val s = window.snapshot(noon)!!

        assertEquals(3, s.previousRequests)
        assertEquals(10, s.previousP95Ms)
    }

    @Test
    fun `a made-up http method cannot grow the route keys`() {
        val window = HttpTrafficWindow()
        (1..50).forEach { window.record("CUSTOM$it", "/api/auth/login", 405, 1, noon) }

        val s = window.snapshot(noon)!!

        assertEquals(listOf(HttpTrafficWindow.RouteCount(405, "OTHER", "/api/auth/login", 50)), s.top4xx)
        assertNull(window.routePercentiles("CUSTOM1", "/api/auth/login"))
        assertEquals(1, window.routePercentiles("OTHER", "/api/auth/login")?.p95Ms)
    }

    @Test
    fun `percentiles sit within one bin of the real value and never above the maximum`() {
        val window = HttpTrafficWindow()
        (1L..1000L).forEach { window.record("GET", "/api/x", 200, it, noon) }

        val s = window.snapshot(noon)!!

        assertTrue(s.p50Ms in 500..550, "p50 ${s.p50Ms}")
        assertTrue(s.p95Ms in 950..1000, "p95 ${s.p95Ms}")
        assertTrue(s.p99Ms in 990..1000, "p99 ${s.p99Ms}")
        assertEquals(0, HttpTrafficWindow().also { it.record("GET", "/a", 200, 0, noon) }.snapshot(noon)!!.p95Ms)
        assertEquals(100, HttpTrafficWindow().also { it.record("GET", "/a", 200, 100, noon) }.snapshot(noon)!!.p95Ms)
        assertEquals(HttpTrafficWindow.RoutePercentiles(s.p95Ms, s.p99Ms), window.routePercentiles("GET", "/api/x"))
        assertNull(window.routePercentiles("POST", "/api/x"))
    }

    @Test
    fun `peak is the busiest single second, top 4xx is ranked and capped`() {
        val window = HttpTrafficWindow()
        window.record("GET", "/api/x", 200, 1, noon.minusSeconds(1))
        repeat(3) { window.record("GET", "/api/x", 200, 1, noon) }
        repeat(5) { window.record("POST", "/api/a", 422, 1, noon) }
        repeat(4) { window.record("GET", "/api/b", 404, 1, noon) }
        repeat(3) { window.record("GET", "/api/c", 401, 1, noon) }
        repeat(2) { window.record("GET", "/api/d", 403, 1, noon) }
        window.record("GET", "/api/e", 400, 1, noon)

        val s = window.snapshot(noon)!!

        assertEquals(18, s.peakRps)
        assertEquals(noon, s.peakAt)
        assertEquals(
            listOf(
                HttpTrafficWindow.RouteCount(422, "POST", "/api/a", 5),
                HttpTrafficWindow.RouteCount(404, "GET", "/api/b", 4),
                HttpTrafficWindow.RouteCount(401, "GET", "/api/c", 3),
                HttpTrafficWindow.RouteCount(403, "GET", "/api/d", 2),
            ),
            s.top4xx,
        )
    }
}
