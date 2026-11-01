package com.shifa.admin.application

import com.shifa.admin.infrastructure.TrafficRollupRepository
import com.shifa.shared.api.HttpTrafficWindow
import com.shifa.shared.api.LatencyHistogram
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TrafficRollupServiceTest {
    private val now = Instant.parse("2026-11-20T12:30:00Z")
    private fun hoursAgo(hours: Long) = Instant.parse("2026-11-20T12:00:00Z").minusSeconds(hours * 3600)

    private fun row(hour: Instant, requests: Long, ms: Long = 10, errors: Long = 0): TrafficRollupRepository.HourRow {
        val latency = LatencyHistogram().also { h -> repeat(requests.toInt()) { h.add(ms) } }
        return TrafficRollupRepository.HourRow(hour, requests, mapOf("2xx" to requests - errors, "5xx" to errors), latency, requests, hour)
    }

    private fun summarize(range: MetricsRange, vararg rows: TrafficRollupRepository.HourRow, earliest: Instant? = rows.firstOrNull()?.hour) =
        TrafficRollupService.summarize(range, rows.toList(), emptyList(), earliest, now)

    @Test
    fun `each range has its bucket count, oldest first, ending in the running bucket`() {
        val day = summarize(MetricsRange.DAY)
        assertEquals(24, day.buckets.size)
        assertEquals(hoursAgo(23), day.from)
        assertEquals(hoursAgo(0), day.buckets.last().start)

        val week = summarize(MetricsRange.WEEK)
        assertEquals(28, week.buckets.size)
        assertEquals(Instant.parse("2026-11-20T12:00:00Z"), week.buckets.last().start)
        assertEquals(Instant.parse("2026-11-13T18:00:00Z"), week.from)

        val month = summarize(MetricsRange.MONTH)
        assertEquals(30, month.buckets.size)
        assertEquals(Instant.parse("2026-11-20T00:00:00Z"), month.buckets.last().start)
        assertEquals(Instant.parse("2026-10-22T00:00:00Z"), month.from)
    }

    @Test
    fun `hours add up inside their bucket and hours before the window stay out of the totals`() {
        val s = summarize(
            MetricsRange.WEEK,
            row(Instant.parse("2026-11-13T17:00:00Z"), 99),
            row(Instant.parse("2026-11-20T06:00:00Z"), 3, errors = 1),
            row(Instant.parse("2026-11-20T07:00:00Z"), 4),
            row(Instant.parse("2026-11-20T12:00:00Z"), 5),
        )
        assertEquals(12, s.requests)
        assertEquals(mapOf("2xx" to 11L, "304" to 0L, "3xxOther" to 0L, "401" to 0L, "403" to 0L, "4xxOther" to 0L, "5xx" to 1L), s.byStatus)
        assertEquals(7, s.buckets[26].requests)
        assertEquals(1, s.buckets[26].serverErrors)
        assertEquals(5, s.buckets[27].requests)
        assertEquals(0, s.buckets[0].requests)
        assertNull(s.buckets[0].p95Ms)
    }

    @Test
    fun `the comparison leaves out the running bucket and its counterpart one range earlier`() {
        val s = summarize(
            MetricsRange.DAY,
            row(hoursAgo(48), 1),
            row(hoursAgo(25), 7, ms = 100), // same hour yesterday as the complete one below
            row(hoursAgo(24), 1000, ms = 9000), // the running hour's counterpart
            row(hoursAgo(1), 9),
            row(hoursAgo(0), 2),
        )
        assertEquals(11, s.requests)
        assertEquals(9, s.comparableRequests)
        assertEquals(7, s.previousRequests)
        assertEquals(100, s.previousP95Ms)
        assertEquals(10, s.comparableP95Ms)
    }

    @Test
    fun `no comparison until the earlier range was recorded from start to end`() {
        val s = summarize(MetricsRange.DAY, row(hoursAgo(47), 5), row(hoursAgo(1), 9))
        assertNull(s.previousRequests)
        assertNull(s.previousP95Ms)
        assertEquals(hoursAgo(47), s.collectingSince)
    }

    @Test
    fun `no rows is an empty view, not an error`() {
        val s = summarize(MetricsRange.MONTH, earliest = null)
        assertEquals(0, s.requests)
        assertNull(s.p95Ms)
        assertNull(s.peakAt)
        assertNull(s.collectingSince)
    }

    @Test
    fun `a save inside a busy second does not split its peak, and an hour with nothing new saves nothing`() {
        val window = HttpTrafficWindow()
        val at = Instant.parse("2026-11-20T12:30:01Z")
        repeat(10) { window.record("GET", "/api/x", 200, 1, at) }
        assertEquals(10, window.drain().single().peakRps)
        assertEquals(emptyList<HttpTrafficWindow.HourDelta>(), window.drain())

        repeat(10) { window.record("GET", "/api/x", 200, 1, at) }
        val second = window.drain().single()
        assertEquals(20, second.peakRps)
        assertEquals(at, second.peakAt)
        assertEquals(10, second.requests)
    }

    @Test
    fun `an unknown range is refused`() {
        assertEquals(MetricsRange.WEEK, MetricsRange.of("7d"))
        assertThrows<IllegalArgumentException> { MetricsRange.of("1y") }
    }

    @Test
    fun `a failed save hands the counts back, nothing is counted twice or lost`() {
        val window = HttpTrafficWindow()
        val at = Instant.parse("2026-11-20T12:30:01Z")
        repeat(3) { window.record("GET", "/api/x", 200, 10, at) }
        window.record("GET", "/api/x", 404, 10, at)

        val first = window.drain()
        assertEquals(4, first.single().requests)
        assertEquals(emptyList<HttpTrafficWindow.HourDelta>(), window.drain())

        window.restore(first)
        window.record("GET", "/api/x", 200, 10, at)
        val second = window.drain().single()
        assertEquals(5, second.requests)
        assertEquals(4, second.byStatus["2xx"])
        assertEquals(listOf(HttpTrafficWindow.RouteCount(404, "GET", "/api/x", 1)), second.clientErrors)
        assertEquals(5, second.latency.total)
    }
}
