package com.shifa.appointments.infrastructure

import com.shifa.appointments.domain.AppointmentFilter
import com.shifa.appointments.domain.AppointmentIntelligenceQueryParser
import com.shifa.appointments.domain.DocumentationFilter
import com.shifa.appointments.domain.GroupDimension
import com.shifa.appointments.domain.Modality

import java.sql.Timestamp

internal data class SqlStatement(
    val sql: String,
    val params: Map<String, Any?>,
)

data class MoneyByCurrency(
    val currency: String,
    val minor: Long,
    val count: Long,
)

data class CountGroup(
    val key: String,
    val count: Long,
)

data class ListedVisit(
    val appointmentId: Long,
    val patientId: Long,
    val patientName: String,
    val startAt: java.time.Instant,
    val status: String,
    val location: String,
    val paymentStatus: String,
)

/**
 * Fixed SQL for appointment intelligence. Doctor id, instants, and enum values are bound
 * parameters. Count queries do not join payments or notes, so extra transactions cannot
 * duplicate an appointment.
 */
internal object AppointmentIntelligenceSql {
    private val STATUSES = setOf("REQUESTED", "CONFIRMED", "IN_PROGRESS", "CANCELLED", "COMPLETED", "NO_SHOW")

    fun count(doctorId: Long, filter: AppointmentFilter): SqlStatement {
        val where = where(doctorId, filter)
        return SqlStatement("SELECT COUNT(*) FROM appointments a WHERE ${where.sql}", where.params)
    }

    fun distinctPatients(doctorId: Long, filter: AppointmentFilter): SqlStatement {
        val where = where(doctorId, filter)
        return SqlStatement(
            "SELECT COUNT(DISTINCT a.patient_id) FROM appointments a WHERE ${where.sql}",
            where.params,
        )
    }

    fun group(doctorId: Long, filter: AppointmentFilter, dimension: GroupDimension): SqlStatement {
        val where = where(doctorId, filter)
        val params = where.params.toMutableMap()
        params["tz"] = filter.zoneId
        val (select, groupBy, order, limit, from) = when (dimension) {
            GroupDimension.STATUS -> Tuple(
                "a.status AS bucket, COUNT(*) AS n",
                "a.status",
                "n DESC, a.status",
                400,
                "appointments a",
            )
            GroupDimension.LOCAL_DAY -> Tuple(
                "to_char(a.start_at AT TIME ZONE :tz, 'YYYY-MM-DD') AS bucket, COUNT(*) AS n",
                "1",
                "1",
                400,
                "appointments a",
            )
            GroupDimension.LOCAL_WEEK -> Tuple(
                "to_char(date_trunc('week', a.start_at AT TIME ZONE :tz), 'YYYY-MM-DD') AS bucket, COUNT(*) AS n",
                "1",
                "1",
                400,
                "appointments a",
            )
            GroupDimension.LOCAL_MONTH -> Tuple(
                "to_char(date_trunc('month', a.start_at AT TIME ZONE :tz), 'YYYY-MM') AS bucket, COUNT(*) AS n",
                "1",
                "1",
                400,
                "appointments a",
            )
            GroupDimension.PAYMENT_STATUS -> Tuple(
                "a.payment_status AS bucket, COUNT(*) AS n",
                "a.payment_status",
                "n DESC, a.payment_status",
                20,
                "appointments a",
            )
            GroupDimension.MODALITY -> Tuple(
                "CASE WHEN LOWER(a.location) LIKE '%video%' THEN 'VIDEO' ELSE 'IN_PERSON' END AS bucket, COUNT(*) AS n",
                "1",
                "n DESC",
                10,
                "appointments a",
            )
            GroupDimension.PATIENT -> Tuple(
                "COALESCE(p.full_name, 'Patient') AS bucket, COUNT(*) AS n",
                "a.patient_id, p.full_name",
                "n DESC, bucket",
                20,
                "appointments a LEFT JOIN patient_profiles p ON p.id = a.patient_id",
            )
        }
        return SqlStatement(
            """
            SELECT $select
            FROM $from
            WHERE ${where.sql}
            GROUP BY $groupBy
            ORDER BY $order
            LIMIT $limit
            """.trimIndent(),
            params,
        )
    }

    fun list(doctorId: Long, filter: AppointmentFilter, descending: Boolean, limit: Int): SqlStatement {
        val where = where(doctorId, filter)
        val params = where.params.toMutableMap()
        val bounded = limit.coerceIn(1, AppointmentIntelligenceQueryParser.MAX_LIST)
        params["rowLimit"] = bounded
        val direction = if (descending) "DESC" else "ASC"
        return SqlStatement(
            """
            SELECT a.id, a.patient_id, COALESCE(p.full_name, 'Patient') AS patient_name,
                   a.start_at, a.status, a.location, a.payment_status
            FROM appointments a
            LEFT JOIN patient_profiles p ON p.id = a.patient_id
            WHERE ${where.sql}
            ORDER BY a.start_at $direction
            LIMIT :rowLimit
            """.trimIndent(),
            params,
        )
    }

    fun consultationCollected(doctorId: Long, start: java.time.Instant, end: java.time.Instant): SqlStatement =
        SqlStatement(
            """
            SELECT currency,
                   COALESCE(SUM(amount_minor), 0) AS total_minor,
                   COUNT(*) AS row_count
            FROM payments
            WHERE kind = 'CONSULTATION'
              AND status = 'PAID'
              AND payee_doctor_id = :doctorId
              AND paid_at >= :startInclusive
              AND paid_at < :endExclusive
            GROUP BY currency
            """.trimIndent(),
            mapOf(
                "doctorId" to doctorId,
                "startInclusive" to Timestamp.from(start),
                "endExclusive" to Timestamp.from(end),
            ),
        )

    fun ledgerCollected(doctorId: Long, start: java.time.Instant, end: java.time.Instant): SqlStatement =
        SqlStatement(
            """
            SELECT tpp.currency AS currency,
                   COALESCE(SUM(tpp.amount_minor), 0) AS total_minor,
                   COUNT(*) AS row_count
            FROM treatment_plan_payments tpp
            JOIN treatment_plans tp ON tp.id = tpp.plan_id
            WHERE tp.attending_doctor_id = :doctorId
              AND tpp.method <> 'WAIVED'
              AND tpp.recorded_at >= :startInclusive
              AND tpp.recorded_at < :endExclusive
            GROUP BY tpp.currency
            """.trimIndent(),
            mapOf(
                "doctorId" to doctorId,
                "startInclusive" to Timestamp.from(start),
                "endExclusive" to Timestamp.from(end),
            ),
        )

    fun appointmentValue(doctorId: Long, filter: AppointmentFilter): SqlStatement {
        val where = where(doctorId, filter)
        return SqlStatement(
            """
            SELECT COALESCE(NULLIF(a.payment_currency, ''), 'UNKNOWN') AS currency,
                   COALESCE(SUM(a.payment_amount_minor), 0) AS total_minor,
                   COUNT(*) AS row_count
            FROM appointments a
            WHERE ${where.sql}
              AND a.payment_amount_minor IS NOT NULL
            GROUP BY 1
            """.trimIndent(),
            where.params,
        )
    }

    fun pendingAppointmentValue(doctorId: Long, filter: AppointmentFilter): SqlStatement {
        val where = where(doctorId, filter)
        return SqlStatement(
            """
            SELECT COALESCE(NULLIF(a.payment_currency, ''), 'UNKNOWN') AS currency,
                   COALESCE(SUM(a.payment_amount_minor), 0) AS total_minor,
                   COUNT(*) AS row_count
            FROM appointments a
            WHERE ${where.sql}
              AND a.payment_status = 'PENDING'
              AND a.payment_amount_minor IS NOT NULL
            GROUP BY 1
            """.trimIndent(),
            where.params,
        )
    }

    private fun where(doctorId: Long, filter: AppointmentFilter): SqlStatement {
        val clauses = mutableListOf("a.doctor_id = :doctorId")
        val params = mutableMapOf<String, Any?>(
            "doctorId" to doctorId,
            "startInclusive" to Timestamp.from(filter.startInclusive),
            "endExclusive" to Timestamp.from(filter.endExclusive),
        )
        clauses += "a.start_at >= :startInclusive"
        clauses += "a.start_at < :endExclusive"
        if (filter.statuses.isNotEmpty()) {
            require(filter.statuses.all { it in STATUSES }) { "status" }
            clauses += "a.status IN (:statuses)"
            params["statuses"] = filter.statuses.toList()
        } else {
            clauses += "a.status NOT IN ('EXPIRED', 'PAYMENT_FAILED')"
        }
        if (filter.upcomingOnly) {
            clauses += "a.start_at >= :now"
            clauses += "a.status IN ('REQUESTED', 'CONFIRMED', 'IN_PROGRESS')"
            params["now"] = Timestamp.from(filter.now)
        }
        if (filter.excludeFuture) {
            clauses += "a.start_at < :now"
            params["now"] = Timestamp.from(filter.now)
        }
        filter.patientId?.let {
            clauses += "a.patient_id = :patientId"
            params["patientId"] = it
        }
        when (filter.documentation) {
            DocumentationFilter.NO_CONSULTATION_NOTE ->
                clauses += "NOT EXISTS (SELECT 1 FROM consultation_notes n WHERE n.appointment_id = a.id)"
            DocumentationFilter.HAS_CONSULTATION_NOTE ->
                clauses += "EXISTS (SELECT 1 FROM consultation_notes n WHERE n.appointment_id = a.id)"
            DocumentationFilter.PATIENT_SIGNED -> clauses += "a.patient_signed_at IS NOT NULL"
            DocumentationFilter.PATIENT_UNSIGNED ->
                clauses += "a.signature_requested = TRUE AND a.patient_signed_at IS NULL"
            null -> Unit
        }
        filter.paymentStatus?.let {
            clauses += "a.payment_status = :paymentStatus"
            params["paymentStatus"] = it.name
        }
        when (filter.modality) {
            Modality.VIDEO -> clauses += "LOWER(a.location) LIKE '%video%'"
            Modality.IN_PERSON -> clauses += "LOWER(a.location) NOT LIKE '%video%'"
            null -> Unit
        }
        return SqlStatement(clauses.joinToString(" AND "), params)
    }

    private data class Tuple(
        val select: String,
        val groupBy: String,
        val order: String,
        val limit: Int,
        val from: String,
    )
}
