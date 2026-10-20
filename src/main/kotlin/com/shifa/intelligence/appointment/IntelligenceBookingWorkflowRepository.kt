package com.shifa.intelligence.appointment

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Repository
class IntelligenceBookingWorkflowRepository(
    private val jdbc: JdbcTemplate,
) {
    private val mapper = RowMapper<IntelligenceBookingWorkflow> { rs: ResultSet, _ ->
        IntelligenceBookingWorkflow(
            id = rs.getObject("id", UUID::class.java),
            doctorId = rs.getLong("doctor_id"),
            conversationId = rs.getString("conversation_id"),
            status = BookingWorkflowStatus.valueOf(rs.getString("status")),
            patientId = rs.getObject("patient_id")?.let { (it as Number).toLong() },
            patientName = rs.getString("patient_name"),
            appointmentDate = rs.getObject("appointment_date", LocalDate::class.java),
            appointmentTime = rs.getObject("appointment_time", LocalTime::class.java),
            proposalJson = rs.getString("proposal_json"),
            confirmationToken = rs.getObject("confirmation_token")?.let { it as UUID },
            idempotencyKey = rs.getObject("idempotency_key", UUID::class.java),
            appointmentId = rs.getObject("appointment_id")?.let { (it as Number).toLong() },
            pendingQuestionType = rs.getString("pending_question_type")?.let(PendingQuestionType::valueOf),
            pendingQuestionJson = rs.getString("pending_question_json"),
            pendingQuestionToken = rs.getObject("pending_question_token")?.let { it as UUID },
            version = rs.getLong("version"),
            expiresAt = rs.getTimestamp("expires_at").toInstant(),
        )
    }

    private val columns = """
        id, doctor_id, conversation_id, status, patient_id, patient_name,
        appointment_date, appointment_time, proposal_json, confirmation_token,
        idempotency_key, appointment_id, pending_question_type, pending_question_json,
        pending_question_token, version, expires_at
    """.trimIndent()

    fun findActive(doctorId: Long, conversationId: String, now: Instant): IntelligenceBookingWorkflow? {
        expireOld(doctorId, conversationId, now)
        return jdbc.query(
            """
            SELECT $columns FROM intelligence_booking_workflows
            WHERE doctor_id = ? AND conversation_id = ?
              AND status IN ('COLLECTING_DETAILS','CHECKING_AVAILABILITY','AWAITING_CONFIRMATION','EXECUTING')
              AND expires_at > ?
            ORDER BY updated_at DESC LIMIT 1
            """.trimIndent(),
            mapper,
            doctorId,
            conversationId,
            Timestamp.from(now),
        ).firstOrNull()
    }

    fun findByIdForDoctor(id: UUID, doctorId: Long): IntelligenceBookingWorkflow? =
        jdbc.query(
            "SELECT $columns FROM intelligence_booking_workflows WHERE id = ? AND doctor_id = ?",
            mapper,
            id,
            doctorId,
        ).firstOrNull()

    fun findLatest(doctorId: Long, conversationId: String): IntelligenceBookingWorkflow? =
        jdbc.query(
            """
            SELECT $columns FROM intelligence_booking_workflows
            WHERE doctor_id = ? AND conversation_id = ?
            ORDER BY updated_at DESC LIMIT 1
            """.trimIndent(),
            mapper,
            doctorId,
            conversationId,
        ).firstOrNull()

    fun insert(workflow: IntelligenceBookingWorkflow) {
        jdbc.update(
            """
            INSERT INTO intelligence_booking_workflows
              (id, doctor_id, conversation_id, status, patient_id, patient_name,
               appointment_date, appointment_time, proposal_json, confirmation_token,
               idempotency_key, appointment_id, pending_question_type, pending_question_json,
               pending_question_token, version, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            """.trimIndent(),
            workflow.id,
            workflow.doctorId,
            workflow.conversationId,
            workflow.status.name,
            workflow.patientId,
            workflow.patientName,
            workflow.appointmentDate,
            workflow.appointmentTime,
            workflow.proposalJson,
            workflow.confirmationToken,
            workflow.idempotencyKey,
            workflow.appointmentId,
            workflow.pendingQuestionType?.name,
            workflow.pendingQuestionJson,
            workflow.pendingQuestionToken,
            workflow.version,
            Timestamp.from(workflow.expiresAt),
        )
    }

    fun update(workflow: IntelligenceBookingWorkflow, expectedVersion: Long): Boolean =
        jdbc.update(
            """
            UPDATE intelligence_booking_workflows
            SET status = ?, patient_id = ?, patient_name = ?, appointment_date = ?,
                appointment_time = ?, proposal_json = ?::jsonb, confirmation_token = ?,
                appointment_id = ?, pending_question_type = ?, pending_question_json = ?::jsonb,
                pending_question_token = ?, expires_at = ?, version = version + 1, updated_at = NOW(),
                completed_at = CASE WHEN ? IN ('COMPLETED','REJECTED','ABANDONED','EXPIRED','FAILED')
                                    THEN NOW() ELSE completed_at END
            WHERE id = ? AND doctor_id = ? AND version = ?
            """.trimIndent(),
            workflow.status.name,
            workflow.patientId,
            workflow.patientName,
            workflow.appointmentDate,
            workflow.appointmentTime,
            workflow.proposalJson,
            workflow.confirmationToken,
            workflow.appointmentId,
            workflow.pendingQuestionType?.name,
            workflow.pendingQuestionJson,
            workflow.pendingQuestionToken,
            Timestamp.from(workflow.expiresAt),
            workflow.status.name,
            workflow.id,
            workflow.doctorId,
            expectedVersion,
        ) == 1

    private fun expireOld(doctorId: Long, conversationId: String, now: Instant) {
        jdbc.update(
            """
            UPDATE intelligence_booking_workflows
            SET status = 'EXPIRED', version = version + 1, updated_at = NOW(), completed_at = NOW()
            WHERE doctor_id = ? AND conversation_id = ?
              AND status IN ('COLLECTING_DETAILS','CHECKING_AVAILABILITY','AWAITING_CONFIRMATION','EXECUTING')
              AND expires_at <= ?
            """.trimIndent(),
            doctorId,
            conversationId,
            Timestamp.from(now),
        )
    }
}
