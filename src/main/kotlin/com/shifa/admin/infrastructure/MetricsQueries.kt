package com.shifa.admin.infrastructure

import java.sql.Timestamp
import java.time.Instant
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
    data class DeadTuples(val table: String, val ratio: Double)
    data class UnusedIndexes(val count: Long, val bytes: Long)
    data class JobRun(val name: String, val lockedAt: Instant)
    data class AiProviderRow(
        val provider: String,
        val use: String?,
        val calls: Long,
        val p95Ms: Long?,
        val errorRatio: Double,
        val tokensIn: Long,
        val tokensOut: Long,
        val fallbacks: Long,
    )

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
            "SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD'), count(*) FROM appointments " +
                "WHERE created_at > now() - make_interval(days => ?) GROUP BY 1 ORDER BY 1",
            { rs, _ -> Row2(rs.getString(1), rs.getLong(2)) },
            days,
        )

    /** Appointments created since [fromEpochSecond], by UTC bucket start in epoch seconds. Buckets without any stay out. */
    fun appointmentsCreatedSince(fromEpochSecond: Long, bucketSeconds: Long): Map<Long, Long> =
        jdbc.query(
            "SELECT floor(extract(epoch FROM created_at) / ?)::bigint, count(*) FROM appointments " +
                "WHERE created_at >= ? GROUP BY 1",
            { rs, _ -> rs.getLong(1) * bucketSeconds to rs.getLong(2) },
            bucketSeconds,
            Timestamp.from(Instant.ofEpochSecond(fromEpochSecond)),
        ).toMap()

    fun paymentsLast24h(): List<Row3> =
        jdbc.query(
            "SELECT status, gateway, count(*) FROM payments WHERE created_at > now() - interval '24 hours' " +
                "GROUP BY 1, 2 ORDER BY 3 DESC",
        ) { rs, _ -> Row3(rs.getString(1), rs.getString(2) ?: "none", rs.getLong(3)) }

    /** ShedLock's `locked_at` is written in UTC (`usingDbTime`) when a job starts, so it is the job's last start. */
    fun jobRuns(): List<JobRun> =
        jdbc.query("SELECT name, locked_at AT TIME ZONE 'UTC' FROM shedlock ORDER BY name") { rs, _ ->
            JobRun(rs.getString(1), rs.getTimestamp(2).toInstant())
        }

    /** A failed call is logged with a duration of 0, so p95 reads successful calls only. */
    fun aiProviders24h(): List<AiProviderRow> =
        jdbc.query(
            "SELECT provider, mode() WITHIN GROUP (ORDER BY purpose), count(*), " +
                "percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) FILTER (WHERE outcome = 'SUCCESS'), " +
                "avg(CASE WHEN outcome = 'SUCCESS' THEN 0.0 ELSE 1.0 END), " +
                "coalesce(sum(input_tokens), 0), coalesce(sum(output_tokens), 0), count(*) FILTER (WHERE fallback_index > 0) " +
                "FROM ai_llm_request_log WHERE created_at > now() - interval '24 hours' GROUP BY 1 ORDER BY 3 DESC",
        ) { rs, _ ->
            AiProviderRow(
                provider = rs.getString(1),
                use = rs.getString(2),
                calls = rs.getLong(3),
                p95Ms = rs.getObject(4)?.let { (it as Number).toLong() },
                errorRatio = rs.getDouble(5),
                tokensIn = rs.getLong(6),
                tokensOut = rs.getLong(7),
                fallbacks = rs.getLong(8),
            )
        }

    /** Null until PostgreSQL has read a block since its statistics were reset. */
    fun cacheHitRatio(): Double? =
        jdbc.queryForObject(
            "SELECT blks_hit::float8 / nullif(blks_hit + blks_read, 0) FROM pg_stat_database WHERE datname = current_database()",
            Double::class.javaObjectType,
        )

    /** The worst table of at least [minRows] rows, smaller ones swing too much to mean anything. */
    fun worstDeadTuples(minRows: Long): DeadTuples? =
        jdbc.query(
            "SELECT relname, n_dead_tup::float8 / (n_live_tup + n_dead_tup) FROM pg_stat_user_tables " +
                "WHERE n_live_tup + n_dead_tup >= ? ORDER BY 2 DESC LIMIT 1",
            { rs, _ -> DeadTuples(rs.getString(1), rs.getDouble(2)) },
            minRows,
        ).firstOrNull()

    /** Unique indexes stay out: they enforce a rule even when no query reads them. */
    fun unusedIndexes(): UnusedIndexes =
        jdbc.queryForObject(
            "SELECT count(*), coalesce(sum(pg_relation_size(s.indexrelid)), 0) FROM pg_stat_user_indexes s " +
                "JOIN pg_index i ON i.indexrelid = s.indexrelid WHERE s.idx_scan = 0 AND NOT i.indisunique",
        ) { rs, _ -> UnusedIndexes(rs.getLong(1), rs.getLong(2)) } ?: UnusedIndexes(0, 0)
}
