package com.shifa.appointments.application

import com.shifa.appointments.domain.BookingWorkflowStatus
import com.shifa.appointments.domain.IntelligenceBookingWorkflow
import com.shifa.appointments.infrastructure.IntelligenceBookingWorkflowRepository
import com.shifa.appointments.domain.PendingPatientCandidate
import com.shifa.appointments.domain.PendingQuestionType

import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.appointments.domain.Appointment
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
    private val rescheduleService: DoctorAppointmentRescheduleService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun active(doctorId: Long, conversationId: String?): IntelligenceBookingWorkflow? {
        val conversation = validConversationId(conversationId) ?: return null
        return repository.findActive(doctorId, conversation, clock.instant())
    }

    @Transactional(readOnly = true)
    fun activeOpen(doctorId: Long, conversationId: String?): IntelligenceBookingWorkflow? {
        val conversation = validConversationId(conversationId) ?: return null
        return repository.findActive(doctorId, openConversation(conversation), clock.instant())
    }

    @Transactional(readOnly = true)
    fun activeReschedule(doctorId: Long, conversationId: String?): IntelligenceBookingWorkflow? {
        val conversation = validConversationId(conversationId) ?: return null
        return repository.findActive(doctorId, rescheduleConversation(conversation), clock.instant())
    }

    internal fun rescheduleContextLine(workflow: IntelligenceBookingWorkflow): String =
        "Active RESCHEDULE proposal ${workflow.id}: appointmentId=${workflow.appointmentId}, " +
            "newDate=${workflow.appointmentDate}, newTime=${workflow.appointmentTime}, status=${workflow.status}. " +
            "A confirmation or rejection applies only to this exact proposal. Call propose_reschedule with " +
            "turnType CONFIRM or REJECT. Do not select another appointment from an affirmative answer."

    internal fun contextLine(workflow: IntelligenceBookingWorkflow): String =
        "Active BOOK workflow ${workflow.id}: status=${workflow.status}, " +
            "patient=${workflow.patientName ?: "missing"} (trusted patientId=${workflow.patientId ?: "missing"}), " +
            "date=${workflow.appointmentDate ?: "missing"}, time=${workflow.appointmentTime ?: "missing"}. " +
            pendingContext(workflow) +
            "Interpret the latest message against this workflow. Use manage_booking_workflow for slot answers, " +
            "corrections, confirmation, rejection, or abandonment. A read-only question may use its normal read tool " +
            "without changing this workflow. Never send the trusted patientId as a model-generated slot."

    internal fun pendingPatientCandidates(workflow: IntelligenceBookingWorkflow): List<PendingPatientCandidate> {
        if (workflow.pendingQuestionType == null || workflow.pendingQuestionJson.isNullOrBlank()) return emptyList()
        return runCatching {
            objectMapper.readValue(
                workflow.pendingQuestionJson,
                object : TypeReference<List<PendingPatientCandidate>>() {},
            )
        }.getOrDefault(emptyList())
    }

    @Transactional
    fun setPendingOpenPatient(
        doctor: DoctorProfile,
        conversationId: String,
        originalName: String?,
        candidates: List<PendingPatientCandidate>,
    ): IntelligenceBookingWorkflow = setPendingPatientQuestion(
        doctor = doctor,
        conversationId = openConversation(conversationId),
        originalName = originalName,
        candidates = candidates,
        date = null,
        time = null,
    )

    @Transactional
    fun resolvePendingOpenPatient(
        doctorId: Long,
        conversationId: String,
        patientId: Long,
    ): IntelligenceBookingWorkflow? = resolvePendingPatient(doctorId, openConversation(conversationId), patientId)

    @Transactional
    fun clearPendingOpenPatient(
        doctorId: Long,
        conversationId: String,
    ): IntelligenceBookingWorkflow? = clearPendingPatient(doctorId, openConversation(conversationId), clearPatientName = true)

    @Transactional
    fun setPendingPatientQuestion(
        doctor: DoctorProfile,
        conversationId: String,
        originalName: String?,
        candidates: List<PendingPatientCandidate>,
        date: LocalDate?,
        time: LocalTime?,
    ): IntelligenceBookingWorkflow {
        require(candidates.isNotEmpty()) { "A pending patient question requires candidates" }
        val base = saveDetails(doctor, conversationId, null, originalName, date, time)
        val next = base.copy(
            patientId = null,
            pendingQuestionType = if (candidates.size == 1) {
                PendingQuestionType.CONFIRM_PATIENT
            } else {
                PendingQuestionType.CHOOSE_PATIENT
            },
            pendingQuestionJson = objectMapper.writeValueAsString(candidates),
            pendingQuestionToken = UUID.randomUUID(),
            expiresAt = clock.instant().plus(TTL),
        )
        check(repository.update(next, base.version)) { "Booking workflow was updated by another request" }
        logTransition(next, base.status, "pending_patient_question")
        return next.copy(version = base.version + 1)
    }

    @Transactional
    fun resolvePendingPatient(
        doctorId: Long,
        conversationId: String,
        patientId: Long,
    ): IntelligenceBookingWorkflow? {
        val current = repository.findActive(doctorId, requireConversationId(conversationId), clock.instant())
            ?: return null
        val candidate = pendingPatientCandidates(current).singleOrNull { it.patientId == patientId } ?: return null
        val next = current.copy(
            patientId = candidate.patientId,
            patientName = candidate.patientName,
            pendingQuestionType = null,
            pendingQuestionJson = null,
            pendingQuestionToken = null,
            proposalJson = null,
            confirmationToken = null,
            expiresAt = clock.instant().plus(TTL),
        )
        check(repository.update(next, current.version)) { "Booking workflow was updated by another request" }
        logTransition(next, current.status, "patient_resolved")
        return next.copy(version = current.version + 1)
    }

    @Transactional
    fun clearPendingPatient(
        doctorId: Long,
        conversationId: String,
        clearPatientName: Boolean,
    ): IntelligenceBookingWorkflow? {
        val current = repository.findActive(doctorId, requireConversationId(conversationId), clock.instant())
            ?: return null
        val next = current.copy(
            patientId = null,
            patientName = if (clearPatientName) null else current.patientName,
            pendingQuestionType = null,
            pendingQuestionJson = null,
            pendingQuestionToken = null,
            proposalJson = null,
            confirmationToken = null,
            expiresAt = clock.instant().plus(TTL),
        )
        check(repository.update(next, current.version)) { "Booking workflow was updated by another request" }
        logTransition(next, current.status, "pending_patient_cleared")
        return next.copy(version = current.version + 1)
    }

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

    @Transactional
    fun attachRescheduleProposal(
        doctor: DoctorProfile,
        conversationId: String,
        payload: Map<String, Any?>,
    ): Map<String, Any?> {
        val preferred = payload["preferredSlot"] as? Map<*, *> ?: return payload
        val appointmentId = (payload["appointmentId"] as? Number)?.toLong() ?: return payload
        val patientId = (payload["patientId"] as? Number)?.toLong()
        val date = payload["date"]?.toString()?.let(LocalDate::parse)
        val time = preferred["startLocal"]?.toString()?.let(LocalTime::parse)
        val key = rescheduleConversation(requireConversationId(conversationId))
        val now = clock.instant()
        val current = repository.findActive(doctor.id, key, now)
        val base = IntelligenceBookingWorkflow(
            id = current?.id ?: UUID.randomUUID(),
            doctorId = doctor.id,
            conversationId = key,
            status = BookingWorkflowStatus.AWAITING_CONFIRMATION,
            patientId = patientId,
            patientName = payload["patientName"]?.toString(),
            appointmentDate = date,
            appointmentTime = time,
            proposalJson = objectMapper.writeValueAsString(payload),
            confirmationToken = UUID.randomUUID(),
            idempotencyKey = current?.idempotencyKey ?: UUID.randomUUID(),
            appointmentId = appointmentId,
            version = current?.version ?: 0,
            expiresAt = now.plus(TTL),
        )
        if (current == null) {
            repository.insert(base)
        } else {
            check(repository.update(base, current.version)) { "Reschedule workflow was updated by another request" }
        }
        LOGGER.info(
            "intelligence_workflow transition workflowId={} conversationId={} type=RESCHEDULE to={} " +
                "appointmentId={} outcome=proposal_created",
            base.id, conversationId, base.status, appointmentId,
        )
        return LinkedHashMap(payload).apply {
            put("workflowId", base.id.toString())
            put("workflowStatus", base.status.name)
            put("confirmationToken", base.confirmationToken.toString())
            put("autoConfirm", false)
        }
    }

    @Transactional
    fun rejectReschedule(doctorId: Long, conversationId: String): Map<String, Any?> {
        val key = rescheduleConversation(requireConversationId(conversationId))
        val current = repository.findActive(doctorId, key, clock.instant()) ?: return noActiveReschedule()
        check(repository.update(current.copy(status = BookingWorkflowStatus.REJECTED), current.version)) {
            "Reschedule workflow was updated by another request"
        }
        return mapOf(
            "cardType" to "propose_reschedule",
            "workflowId" to current.id.toString(),
            "workflowStatus" to BookingWorkflowStatus.REJECTED.name,
            "spokenHint" to "I did not move that appointment.",
        )
    }

    fun confirmReschedule(doctor: DoctorProfile, conversationId: String): Map<String, Any?> {
        val key = rescheduleConversation(requireConversationId(conversationId))
        val current = repository.findActive(doctor.id, key, clock.instant())
        if (current == null) {
            val latest = repository.findLatest(doctor.id, key)
            if (latest?.status == BookingWorkflowStatus.COMPLETED && latest.appointmentId != null) {
                return completedReschedulePayload(latest, replay = true)
            }
            return noActiveReschedule()
        }
        if (current.status != BookingWorkflowStatus.AWAITING_CONFIRMATION || current.confirmationToken == null) {
            return noActiveReschedule()
        }
        val executing = current.copy(status = BookingWorkflowStatus.EXECUTING)
        check(repository.update(executing, current.version)) {
            "This reschedule confirmation was already consumed"
        }
        return try {
            val proposal = readProposal(current.proposalJson)
            val preferred = proposal["preferredSlot"] as? Map<*, *>
                ?: error("The reschedule proposal has no selected slot")
            val command = DoctorRescheduleCommand(
                appointmentId = current.appointmentId ?: error("Missing appointment reference"),
                expectedStartAt = Instant.parse(proposal["currentStartAt"]?.toString()),
                expectedStatus = Appointment.Status.valueOf(
                    requireNotNull(proposal["currentStatus"]?.toString()) { "Missing source status" },
                ),
                newStartAt = Instant.parse(preferred["startAt"]?.toString()),
                slotMinutes = (preferred["slotMinutes"] as? Number)?.toInt() ?: 30,
                locationId = (preferred["locationId"] as? Number)?.toLong(),
            )
            val moved = rescheduleService.reschedule(doctor, command)
            val completed = executing.copy(
                status = BookingWorkflowStatus.COMPLETED,
                appointmentId = moved.id,
                expiresAt = clock.instant(),
                version = current.version + 1,
            )
            check(repository.update(completed, current.version + 1)) {
                "Appointment moved but workflow result could not be recorded"
            }
            completedReschedulePayload(completed, replay = false)
        } catch (e: Exception) {
            val failed = executing.copy(
                status = BookingWorkflowStatus.FAILED,
                expiresAt = clock.instant(),
                version = current.version + 1,
            )
            repository.update(failed, current.version + 1)
            mapOf(
                "cardType" to "propose_reschedule",
                "workflowId" to current.id.toString(),
                "workflowStatus" to BookingWorkflowStatus.FAILED.name,
                "error" to (e.message ?: "Reschedule failed"),
                "spokenHint" to "The appointment or slot changed, so I did not move it. Please ask again.",
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

    private fun completedReschedulePayload(
        workflow: IntelligenceBookingWorkflow,
        replay: Boolean,
    ): Map<String, Any?> = mapOf(
        "cardType" to "propose_reschedule",
        "workflowId" to workflow.id.toString(),
        "workflowStatus" to BookingWorkflowStatus.COMPLETED.name,
        "appointmentId" to workflow.appointmentId,
        "date" to workflow.appointmentDate?.toString(),
        "requestedTime" to workflow.appointmentTime?.toString(),
        "moved" to true,
        "replayed" to replay,
        "spokenHint" to "Moved the appointment to ${workflow.appointmentTime} on ${workflow.appointmentDate}.",
    )

    private fun noActiveReschedule(): Map<String, Any?> = mapOf(
        "cardType" to "propose_reschedule",
        "workflowStatus" to "NONE",
        "error" to "No active reschedule proposal",
        "spokenHint" to "There is no reschedule waiting for confirmation.",
    )

    private fun validConversationId(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 128 }

    private fun requireConversationId(raw: String?): String =
        requireNotNull(validConversationId(raw)) { "A valid Intelligence conversation id is required" }

    private fun rescheduleConversation(conversationId: String): String =
        "move:${conversationId.take(123)}"

    private fun openConversation(conversationId: String): String =
        "open:${conversationId.take(123)}"

    private fun pendingContext(workflow: IntelligenceBookingWorkflow): String {
        val type = workflow.pendingQuestionType ?: return ""
        val candidates = pendingPatientCandidates(workflow)
            .joinToString("; ") { "${it.patientId}:${it.patientName}" }
        return "Pending question type=$type token=${workflow.pendingQuestionToken}, candidates=[$candidates]. " +
            "The latest utterance answers this question unless it clearly starts an unrelated request. " +
            "Return manage_booking_workflow with CONFIRM, REJECT, CORRECT_SLOT, or AMBIGUOUS; " +
            "do not call search_patients for a confirmation answer. "
    }

    private fun logTransition(
        workflow: IntelligenceBookingWorkflow,
        from: BookingWorkflowStatus,
        outcome: String,
    ) {
        LOGGER.info(
            "intelligence_workflow transition workflowId={} conversationId={} type=BOOK from={} to={} " +
                "pendingQuestion={} patientId={} outcome={}",
            workflow.id,
            workflow.conversationId,
            from,
            workflow.status,
            workflow.pendingQuestionType,
            workflow.patientId,
            outcome,
        )
    }

    companion object {
        val TTL: Duration = Duration.ofMinutes(30)
        private val LOGGER = org.slf4j.LoggerFactory.getLogger(IntelligenceBookingWorkflowService::class.java)
    }
}
