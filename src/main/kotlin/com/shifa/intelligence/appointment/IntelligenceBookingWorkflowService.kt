package com.shifa.intelligence.appointment

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.domain.DoctorProfile
import com.shifa.service.DoctorAppointmentBookingService
import com.shifa.service.DoctorBookingCommand
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Service
class IntelligenceBookingWorkflowService(
    private val repository: IntelligenceBookingWorkflowRepository,
    private val bookingService: DoctorAppointmentBookingService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    fun active(doctorId: Long, conversationId: String?): IntelligenceBookingWorkflow? {
        val conversation = validConversationId(conversationId) ?: return null
        return repository.findActive(doctorId, conversation, clock.instant())
    }

    fun contextLine(workflow: IntelligenceBookingWorkflow): String =
        "Active BOOK workflow ${workflow.id}: status=${workflow.status}, " +
            "patient=${workflow.patientName ?: "missing"} (trusted patientId=${workflow.patientId ?: "missing"}), " +
            "date=${workflow.appointmentDate ?: "missing"}, time=${workflow.appointmentTime ?: "missing"}. " +
            "Interpret the latest message against this workflow. Use manage_booking_workflow for slot answers, " +
            "corrections, confirmation, rejection, or abandonment. A read-only question may use its normal read tool " +
            "without changing this workflow. Never send the trusted patientId as a model-generated slot."

    @Transactional
    fun saveDetails(
        doctor: DoctorProfile,
        conversationId: String,
        patientId: Long?,
        patientName: String?,
        date: LocalDate?,
        time: LocalTime?,
    ): IntelligenceBookingWorkflow {
        val conversation = requireConversationId(conversationId)
        val now = clock.instant()
        val current = repository.findActive(doctor.id, conversation, now)
        if (current == null) {
            val created = IntelligenceBookingWorkflow(
                id = UUID.randomUUID(),
                doctorId = doctor.id,
                conversationId = conversation,
                status = BookingWorkflowStatus.COLLECTING_DETAILS,
                patientId = patientId,
                patientName = patientName?.take(255),
                appointmentDate = date,
                appointmentTime = time,
                proposalJson = null,
                confirmationToken = null,
                idempotencyKey = UUID.randomUUID(),
                appointmentId = null,
                version = 0,
                expiresAt = now.plus(TTL),
            )
            repository.insert(created)
            return created
        }
        val next = current.copy(
            status = BookingWorkflowStatus.COLLECTING_DETAILS,
            patientId = patientId ?: current.patientId,
            patientName = patientName?.take(255) ?: current.patientName,
            appointmentDate = date ?: current.appointmentDate,
            appointmentTime = time ?: current.appointmentTime,
            proposalJson = null,
            confirmationToken = null,
            expiresAt = now.plus(TTL),
        )
        check(repository.update(next, current.version)) { "Booking workflow was updated by another request" }
        return next.copy(version = current.version + 1)
    }

    @Transactional
    fun attachProposal(
        doctor: DoctorProfile,
        conversationId: String,
        payload: Map<String, Any?>,
    ): Map<String, Any?> {
        val patientId = (payload["patientId"] as? Number)?.toLong()
        val patientName = payload["patientName"]?.toString()
        val date = payload["date"]?.toString()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val requestedTime = payload["requestedTime"]?.toString()
            ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        val base = saveDetails(doctor, conversationId, patientId, patientName, date, requestedTime)
        val preferred = payload["preferredSlot"] as? Map<*, *>
        val now = clock.instant()
        val nextStatus = if (preferred != null) {
            BookingWorkflowStatus.AWAITING_CONFIRMATION
        } else {
            BookingWorkflowStatus.COLLECTING_DETAILS
        }
        val next = base.copy(
            status = nextStatus,
            proposalJson = objectMapper.writeValueAsString(payload),
            confirmationToken = if (preferred != null) UUID.randomUUID() else null,
            expiresAt = now.plus(TTL),
        )
        check(repository.update(next, base.version)) { "Booking workflow was updated by another request" }
        val chosenTime = preferred?.get("startLocal")?.toString()
        val spokenHint = when {
            preferred != null ->
                "Please confirm booking ${patientName ?: "the patient"} at $chosenTime on " +
                    "${payload["speakableDateLabel"] ?: payload["dateLabel"] ?: date}."
            else -> payload["spokenHint"]?.toString()
        }
        return LinkedHashMap(payload).apply {
            put("workflowId", next.id.toString())
            put("workflowStatus", nextStatus.name)
            put("confirmationToken", next.confirmationToken?.toString())
            put("autoConfirm", false)
            put("booked", false)
            put("spokenHint", spokenHint)
        }
    }

    @Transactional
    fun reject(doctorId: Long, conversationId: String, abandon: Boolean): Map<String, Any?> {
        val current = repository.findActive(doctorId, requireConversationId(conversationId), clock.instant())
            ?: return noActive()
        val status = if (abandon) BookingWorkflowStatus.ABANDONED else BookingWorkflowStatus.REJECTED
        check(repository.update(current.copy(status = status), current.version)) {
            "Booking workflow was updated by another request"
        }
        return mapOf(
            "cardType" to "propose_appointment",
            "workflowId" to current.id.toString(),
            "workflowStatus" to status.name,
            "spokenHint" to if (abandon) "I abandoned that booking." else "I did not book that appointment.",
        )
    }

    fun confirm(doctor: DoctorProfile, conversationId: String): Map<String, Any?> {
        val conversation = requireConversationId(conversationId)
        val current = repository.findActive(doctor.id, conversation, clock.instant())
        if (current == null) {
            val latest = repository.findLatest(doctor.id, conversation)
            if (latest?.status == BookingWorkflowStatus.COMPLETED && latest.appointmentId != null) {
                return completedPayload(latest, replay = true)
            }
            return noActive()
        }
        if (current.status != BookingWorkflowStatus.AWAITING_CONFIRMATION || current.confirmationToken == null) {
            return mapOf(
                "cardType" to "propose_appointment",
                "workflowId" to current.id.toString(),
                "workflowStatus" to current.status.name,
                "spokenHint" to "There is no exact booking proposal waiting for confirmation.",
            )
        }
        val executing = current.copy(status = BookingWorkflowStatus.EXECUTING)
        check(repository.update(executing, current.version)) { "This booking confirmation was already consumed" }

        return try {
            val proposal = readProposal(current.proposalJson)
            val preferred = proposal["preferredSlot"] as? Map<*, *>
                ?: error("The booking proposal has no selected slot")
            val startAt = Instant.parse(preferred["startAt"]?.toString())
            val slotMinutes = (preferred["slotMinutes"] as? Number)?.toInt() ?: 30
            val patientId = current.patientId ?: error("The booking proposal has no resolved patient")
            val appointment = bookingService.book(
                doctor = doctor,
                actor = doctor.user,
                command = DoctorBookingCommand(
                    patientId = patientId,
                    startAt = startAt,
                    slotMinutes = slotMinutes,
                    locationId = (preferred["locationId"] as? Number)?.toLong(),
                    location = preferred["locationLabel"]?.toString(),
                    reason = proposal["reason"]?.toString(),
                    isVideo = proposal["isVideo"] == true,
                ),
            )
            val completed = executing.copy(
                status = BookingWorkflowStatus.COMPLETED,
                appointmentId = appointment.id,
                expiresAt = clock.instant(),
                version = current.version + 1,
            )
            check(repository.update(completed, current.version + 1)) {
                "Booking completed but workflow result could not be recorded"
            }
            completedPayload(completed, replay = false)
        } catch (e: Exception) {
            val failed = executing.copy(
                status = BookingWorkflowStatus.FAILED,
                expiresAt = clock.instant(),
                version = current.version + 1,
            )
            repository.update(failed, current.version + 1)
            mapOf(
                "cardType" to "propose_appointment",
                "workflowId" to current.id.toString(),
                "workflowStatus" to BookingWorkflowStatus.FAILED.name,
                "error" to (e.message ?: "Booking failed"),
                "spokenHint" to "That slot could not be booked. Please choose another available time.",
            )
        }
    }

    private fun readProposal(raw: String?): Map<String, Any?> =
        if (raw.isNullOrBlank()) emptyMap()
        else objectMapper.readValue(raw, object : TypeReference<Map<String, Any?>>() {})

    private fun completedPayload(workflow: IntelligenceBookingWorkflow, replay: Boolean): Map<String, Any?> =
        mapOf(
            "cardType" to "propose_appointment",
            "workflowId" to workflow.id.toString(),
            "workflowStatus" to BookingWorkflowStatus.COMPLETED.name,
            "appointmentId" to workflow.appointmentId,
            "patientId" to workflow.patientId,
            "patientName" to workflow.patientName,
            "date" to workflow.appointmentDate?.toString(),
            "requestedTime" to workflow.appointmentTime?.toString(),
            "booked" to true,
            "replayed" to replay,
            "spokenHint" to "Booked ${workflow.patientName ?: "the patient"}" +
                (workflow.appointmentTime?.let { " at $it" } ?: "") +
                (workflow.appointmentDate?.let { " on $it" } ?: "") + ".",
        )

    private fun noActive(): Map<String, Any?> = mapOf(
        "cardType" to "propose_appointment",
        "workflowStatus" to "NONE",
        "error" to "No active booking workflow",
        "spokenHint" to "There is no booking waiting for confirmation.",
    )

    private fun validConversationId(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 128 }

    private fun requireConversationId(raw: String?): String =
        requireNotNull(validConversationId(raw)) { "A valid Intelligence conversation id is required" }

    companion object {
        val TTL: Duration = Duration.ofMinutes(30)
    }
}
