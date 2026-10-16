package com.shifa.intelligence.appointment

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import javax.sql.DataSource

interface AppointmentIntelligenceReadModel {
    fun count(doctorId: Long, filter: AppointmentFilter): Long
    fun countDistinctPatients(doctorId: Long, filter: AppointmentFilter): Long
    fun group(doctorId: Long, filter: AppointmentFilter, dimension: GroupDimension): List<CountGroup>
    fun list(doctorId: Long, filter: AppointmentFilter, descending: Boolean, limit: Int): List<ListedVisit>
    fun consultationCollected(doctorId: Long, start: java.time.Instant, end: java.time.Instant): List<MoneyByCurrency>
    fun ledgerCollected(doctorId: Long, start: java.time.Instant, end: java.time.Instant): List<MoneyByCurrency>
    fun appointmentValue(doctorId: Long, filter: AppointmentFilter): List<MoneyByCurrency>
    fun pendingAppointmentValue(doctorId: Long, filter: AppointmentFilter): List<MoneyByCurrency>
}

@Repository
class JdbcAppointmentIntelligenceQueryExecutor(
    dataSource: DataSource,
) : AppointmentIntelligenceReadModel {
    private val jdbc = NamedParameterJdbcTemplate(
        JdbcTemplate(dataSource).apply { queryTimeout = 8 },
    )

    override fun count(doctorId: Long, filter: AppointmentFilter): Long =
        queryLong(AppointmentIntelligenceSql.count(doctorId, filter))

    override fun countDistinctPatients(doctorId: Long, filter: AppointmentFilter): Long =
        queryLong(AppointmentIntelligenceSql.distinctPatients(doctorId, filter))

    override fun group(doctorId: Long, filter: AppointmentFilter, dimension: GroupDimension): List<CountGroup> {
        val statement = AppointmentIntelligenceSql.group(doctorId, filter, dimension)
        return jdbc.query(statement.sql, MapSqlParameterSource(statement.params)) { rs, _ ->
            CountGroup(rs.getString("bucket") ?: "", rs.getLong("n"))
        }
    }

    override fun list(
        doctorId: Long,
        filter: AppointmentFilter,
        descending: Boolean,
        limit: Int,
    ): List<ListedVisit> {
        val statement = AppointmentIntelligenceSql.list(doctorId, filter, descending, limit)
        return jdbc.query(statement.sql, MapSqlParameterSource(statement.params)) { rs, _ ->
            ListedVisit(
                appointmentId = rs.getLong("id"),
                patientId = rs.getLong("patient_id"),
                patientName = rs.getString("patient_name") ?: "Patient",
                startAt = rs.getTimestamp("start_at").toInstant(),
                status = rs.getString("status"),
                location = rs.getString("location") ?: "",
                paymentStatus = rs.getString("payment_status") ?: "",
            )
        }
    }

    override fun consultationCollected(doctorId: Long, start: java.time.Instant, end: java.time.Instant) =
        money(AppointmentIntelligenceSql.consultationCollected(doctorId, start, end))

    override fun ledgerCollected(doctorId: Long, start: java.time.Instant, end: java.time.Instant) =
        money(AppointmentIntelligenceSql.ledgerCollected(doctorId, start, end))

    override fun appointmentValue(doctorId: Long, filter: AppointmentFilter) =
        money(AppointmentIntelligenceSql.appointmentValue(doctorId, filter))

    override fun pendingAppointmentValue(doctorId: Long, filter: AppointmentFilter) =
        money(AppointmentIntelligenceSql.pendingAppointmentValue(doctorId, filter))

    private fun queryLong(statement: SqlStatement): Long =
        jdbc.query(statement.sql, MapSqlParameterSource(statement.params)) { rs, _ -> rs.getLong(1) }
            .firstOrNull() ?: 0L

    private fun money(statement: SqlStatement): List<MoneyByCurrency> =
        jdbc.query(statement.sql, MapSqlParameterSource(statement.params)) { rs, _ ->
            MoneyByCurrency(
                currency = rs.getString("currency") ?: "UNKNOWN",
                minor = number(rs, "total_minor"),
                count = number(rs, "row_count"),
            )
        }

    private fun number(rs: java.sql.ResultSet, column: String): Long = when (val value = rs.getObject(column)) {
        null -> 0L
        is Number -> value.toLong()
        else -> value.toString().toLong()
    }
}
