package com.shifa.admin.infrastructure

import com.shifa.shared.api.HttpTrafficWindow
import com.shifa.shared.api.LatencyHistogram
import java.sql.Timestamp
import java.time.Instant
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.stereotype.Repository

/** The hourly rollups of `/api` traffic. Saving adds to what is there, so two instances overlapping in a deploy both count. */
@Repository
class TrafficRollupRepository(private val jdbc: JdbcTemplate) {
    data class HourRow(
        val hour: Instant,
        val requests: Long,
        val byStatus: Map<String, Long>,
        val latency: LatencyHistogram,
        val peakRps: Long,
        val peakAt: Instant,
    )

    fun add(delta: HttpTrafficWindow.HourDelta) {
        val hour = Timestamp.from(delta.hour)
        jdbc.update(
            UPSERT_HOUR,
            PreparedStatementSetter { ps ->
                var i = 0
                ps.setTimestamp(++i, hour)
                ps.setLong(++i, delta.requests)
                STATUS_COLUMNS.values.forEach { ps.setLong(++i, delta.byStatus[it] ?: 0L) }
                ps.setString(++i, delta.latency.toBins().joinToString(",", "{", "}"))
                ps.setLong(++i, delta.latency.maxMs)
                ps.setLong(++i, delta.peakRps)
                ps.setTimestamp(++i, Timestamp.from(delta.peakAt))
            },
        )
        delta.clientErrors.forEach {
            jdbc.update(UPSERT_4XX, hour, it.status, it.method, it.route, it.count)
        }
    }

    fun hoursFrom(from: Instant): List<HourRow> =
        jdbc.query(
            "SELECT hour, requests, ${STATUS_COLUMNS.keys.joinToString()}, latency_bins, max_ms, peak_rps, peak_at " +
                "FROM ops_http_hourly WHERE hour >= ? ORDER BY hour",
            { rs, _ ->
                HourRow(
                    hour = rs.getTimestamp("hour").toInstant(),
                    requests = rs.getLong("requests"),
                    byStatus = STATUS_COLUMNS.entries.associate { (column, group) -> group to rs.getLong(column) },
                    latency = LatencyHistogram(
                        (rs.getArray("latency_bins").array as Array<*>).map { (it as Number).toLong() }.toLongArray(),
                        rs.getLong("max_ms"),
                    ),
                    peakRps = rs.getLong("peak_rps"),
                    peakAt = rs.getTimestamp("peak_at").toInstant(),
                )
            },
            Timestamp.from(from),
        )

    fun top4xxFrom(from: Instant, limit: Int): List<HttpTrafficWindow.RouteCount> =
        jdbc.query(
            "SELECT status, method, route, sum(requests) FROM ops_http_hourly_4xx WHERE hour >= ? " +
                "GROUP BY 1, 2, 3 ORDER BY 4 DESC LIMIT ?",
            { rs, _ -> HttpTrafficWindow.RouteCount(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getLong(4)) },
            Timestamp.from(from),
            limit,
        )

    fun earliestHour(): Instant? =
        jdbc.queryForObject("SELECT min(hour) FROM ops_http_hourly", Timestamp::class.java)?.toInstant()

    fun deleteBefore(cutoff: Instant) {
        val at = Timestamp.from(cutoff)
        jdbc.update("DELETE FROM ops_http_hourly WHERE hour < ?", at)
        jdbc.update("DELETE FROM ops_http_hourly_4xx WHERE hour < ?", at)
    }

    private companion object {
        val STATUS_COLUMNS = linkedMapOf(
            "status_2xx" to "2xx",
            "status_304" to "304",
            "status_3xx_other" to "3xxOther",
            "status_401" to "401",
            "status_403" to "403",
            "status_4xx_other" to "4xxOther",
            "status_5xx" to "5xx",
        )
        val UPSERT_HOUR =
            "INSERT INTO ops_http_hourly AS t (hour, requests, ${STATUS_COLUMNS.keys.joinToString()}, " +
                "latency_bins, max_ms, peak_rps, peak_at) " +
                "VALUES (?, ?, ${STATUS_COLUMNS.keys.joinToString { "?" }}, ?::bigint[], ?, ?, ?) " +
                "ON CONFLICT (hour) DO UPDATE SET requests = t.requests + EXCLUDED.requests, " +
                STATUS_COLUMNS.keys.joinToString { "$it = t.$it + EXCLUDED.$it" } + ", " +
                "latency_bins = (SELECT array_agg(coalesce(a, 0) + coalesce(b, 0) ORDER BY n) " +
                "FROM unnest(t.latency_bins, EXCLUDED.latency_bins) WITH ORDINALITY AS u(a, b, n)), " +
                "max_ms = GREATEST(t.max_ms, EXCLUDED.max_ms), " +
                "peak_at = CASE WHEN EXCLUDED.peak_rps > t.peak_rps THEN EXCLUDED.peak_at ELSE t.peak_at END, " +
                "peak_rps = GREATEST(t.peak_rps, EXCLUDED.peak_rps)"
        const val UPSERT_4XX =
            "INSERT INTO ops_http_hourly_4xx AS t (hour, status, method, route, requests) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (hour, status, method, route) DO UPDATE SET requests = t.requests + EXCLUDED.requests"
    }
}
