package com.shifa.intelligence.appointment

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

enum class BookingWorkflowStatus {
    COLLECTING_DETAILS,
    CHECKING_AVAILABILITY,
    AWAITING_CONFIRMATION,
    EXECUTING,
    COMPLETED,
    REJECTED,
    ABANDONED,
    EXPIRED,
    FAILED,
}

enum class BookingTurnType {
    START_WORKFLOW,
    SLOT_UPDATE,
    CORRECT_SLOT,
    CONFIRM,
    REJECT,
    ABANDON,
    UNRELATED_QUERY,
    AMBIGUOUS,
}

enum class PendingQuestionType {
    CONFIRM_PATIENT,
    CHOOSE_PATIENT,
}

data class PendingPatientCandidate(
    val patientId: Long,
    val patientName: String,
)

data class IntelligenceBookingWorkflow(
    val id: UUID,
    val doctorId: Long,
    val conversationId: String,
    val status: BookingWorkflowStatus,
    val patientId: Long?,
    val patientName: String?,
    val appointmentDate: LocalDate?,
    val appointmentTime: LocalTime?,
    val proposalJson: String?,
    val confirmationToken: UUID?,
    val idempotencyKey: UUID,
    val appointmentId: Long?,
    val pendingQuestionType: PendingQuestionType? = null,
    val pendingQuestionJson: String? = null,
    val pendingQuestionToken: UUID? = null,
    val version: Long,
    val expiresAt: Instant,
)

data class BookingWorkflowUpdate(
    val turnType: BookingTurnType,
    val patientName: String? = null,
    val dateExpression: String? = null,
    val time: String? = null,
)
