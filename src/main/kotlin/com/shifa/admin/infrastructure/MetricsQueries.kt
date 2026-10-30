package com.shifa.admin.infrastructure

import java.sql.Timestamp
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/**
 * Read-only aggregates for the admin metrics endpoint: PostgreSQL's own statistics views and counts over the
 * business tables. Nothing here returns a row of patient data, only counts and sizes.
 */
@Repository
class MetricsQueries(private val jdbc: JdbcTemplate) {
    data class Row2(val a: String, val b: Long)
    data class Row3(val a: String, val b: String, val c: Long)
    data class TableRow(val table: String, val estimatedRows: Long, val bytes: Long)
    data class Migration(val version: Int, val description: String, val installedOn: Timestamp)
    data class Connections(val active: Long, val idle: Long, val idleInTransaction: Long)

    fun databaseSizeBytes(): Long = jdbc.queryForObject("SELECT pg_database_size(current_database())", Long::class.java) ?: 0

    fun postgresVersion(): String =
        jdbc.queryForObject("SHOW server_version", String::class.java) ?: "unknown"

    fun lastMigration(): Migration? =
        jdbc.query(
            "SELECT version, description, installed_on FROM flyway_schema_history " +
                "WHERE success ORDER BY installed_rank DESC LIMIT 1",
        ) { rs, _ -> Migration(rs.getString(1).toInt(), rs.getString(2), rs.getTimestamp(3)) }.firstOrNull()

    fun connections(): Connections {
        val states = jdbc.query(
            "SELECT state, count(*) FROM pg_stat_activity WHERE datname = current_database() GROUP BY state",
        ) { rs, _ -> Row2(rs.getString(1) ?: "unknown", rs.getLong(2)) }.associate { it.a to it.b }
        return Connections(states["active"] ?: 0, states["idle"] ?: 0, states["idle in transaction"] ?: 0)
    }

    fun largestTables(limit: Int): List<TableRow> =
        jdbc.query(
            "SELECT relname, n_live_tup, pg_total_relation_size(relid) FROM pg_stat_user_tables " +
                "ORDER BY pg_total_relation_size(relid) DESC LIMIT ?",
            { rs, _ -> TableRow(rs.getString(1), rs.getLong(2), rs.getLong(3)) },
            limit,
        )

    fun usersByRole(): List<Row2> =
        jdbc.query("SELECT role, count(*) FROM users GROUP BY role ORDER BY 2 DESC") { rs, _ ->
            Row2(rs.getString(1), rs.getLong(2))
        }

    fun appointmentsByStatus(): List<Row3> =
        jdbc.query(
            "SELECT status, payment_status, count(*) FROM appointments GROUP BY 1, 2 ORDER BY 3 DESC",
        ) { rs, _ -> Row3(rs.getString(1), rs.getString(2), rs.getLong(3)) }

    fun paymentsByStatus(): List<Row3> =
        jdbc.query("SELECT status, gateway, count(*) FROM payments GROUP BY 1, 2 ORDER BY 3 DESC") { rs, _ ->
            Row3(rs.getString(1), rs.getString(2) ?: "none", rs.getLong(3))
        }

    fun notificationCounts(): Pair<Long, Long> =
        jdbc.queryForObject(
            "SELECT count(*), count(*) FILTER (WHERE read_at IS NULL) FROM notifications",
        ) { rs, _ -> rs.getLong(1) to rs.getLong(2) } ?: (0L to 0L)

    fun activeSessions(): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM user_sessions WHERE revoked = false AND expires_at > now()",
            Long::class.java,
        ) ?: 0

    fun appointmentsPerDay(days: Int): List<Row2> =
        jdbc.query(
            "SELECT to_char(date_trunc('day', created_at), 'YYYY-MM-DD'), count(*) FROM appointments " +
                "WHERE created_at > now() - make_interval(days => ?) GROUP BY 1 ORDER BY 1",
            { rs, _ -> Row2(rs.getString(1), rs.getLong(2)) },
            days,
        )
}
