package com.shifa.intelligence.appointment

import com.shifa.domain.Appointment
import com.shifa.domain.DoctorProfile
import com.shifa.repo.AppointmentRepository
import com.shifa.service.AppointmentCancellationService
import com.shifa.service.AppointmentVisitLifecycleService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Write actions for Shifa Intelligence. Cancellation uses [AppointmentCancellationService].
 * Start, notes, and signature use [AppointmentVisitLifecycleService].
 */
@Service
class AppointmentActionService(
    private val appointments: AppointmentRepository,
    private val cancellationService: AppointmentCancellationService,
    private val lifecycle: AppointmentVisitLifecycleService,
    private val ranges: ClinicTimeRangeResolver,
    private val pending: PendingAppointmentActionStore,
    private val clock: Clock,
) {
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)

    @Transactional(readOnly = true)
    fun previewCancellation(
        doctor: DoctorProfile,
        userText: String,
        patientId: Long?,
        appointmentId: Long?,
    ): Map<String, Any?> {
        val zone = ranges.zoneOf(doctor.timeZone)
        val now = clock.instant()
        val selected = select(doctor, userText, patientId, appointmentId, zone)
        if (selected is Selection.NeedsClarification) return selected.payload
        if (selected is Selection.Empty) return selected.payload
        val rows = (selected as Selection.Ready).appointments
        if (rows.size > MAX_BULK) {
            return mapOf(
                "supported" to false,
                "status" to "AWAITING_CLARIFICATION",
                "spokenHint" to "That matches more than $MAX_BULK visits. Narrow the period before I cancel anything.",
            )
        }
        val eligible = mutableListOf<Appointment>()
        val skipped = mutableListOf<Map<String, Any?>>()
        for (appointment in rows) {
            val reason = ineligibleReason(appointment, now)
            if (reason == null) eligible += appointment else skipped += row(appointment, zone) + mapOf("reason" to reason)
        }
        if (eligible.isEmpty()) {
            pending.clear(doctor.id)
            return mapOf(
                "supported" to true,
                "status" to "SUCCESS",
                "cardType" to "cancel_preview",
                "cancelled" to emptyList<Any>(),
                "skipped" to skipped,
                "spokenHint" to "No visits in that period can be cancelled. Nothing was changed.",
            )
        }
        val token = pending.newToken()
        pending.save(
            PendingCancellation(
                token = token,
                doctorId = doctor.id,
                appointmentIds = eligible.map { it.id },
                fingerprints = eligible.associate { it.id to fingerprint(it) },
                createdAt = now,
            ),
        )
        val previewRows = eligible.map { row(it, zone) }
        val names = previewRows.take(6).joinToString("; ") { "${it["patientName"]} ${it["whenLabel"]}" }
        val more = if (previewRows.size > 6) ", and ${previewRows.size - 6} more" else ""
        val skippedNote = if (skipped.isEmpty()) "" else " ${skipped.size} cannot be cancelled and will be left as they are."
        return mapOf(
            "supported" to true,
            "status" to "AWAITING_CONFIRMATION",
            "cardType" to "cancel_preview",
            "actionContext" to mapOf("type" to "CANCEL", "token" to token),
            "appointments" to previewRows,
            "skipped" to skipped,
            "count" to previewRows.size,
            "spokenHint" to "I can cancel ${previewRows.size} upcoming ${if (previewRows.size == 1) "visit" else "visits"}: $names$more.$skippedNote Say yes to cancel them. Nothing has been cancelled yet.",
        )
    }

    @Transactional
    fun confirmCancellation(doctor: DoctorProfile, userText: String, priorAssistantMessages: List<String>): Map<String, Any?> {
        if (!isExplicitConfirmation(userText)) {
            return mapOf(
                "supported" to true,
                "status" to "AWAITING_CONFIRMATION",
                "spokenHint" to "Nothing was cancelled. Say yes if you want me to cancel the visits I just listed.",
            )
        }
        val stored = pending.get(doctor.id)
        val token = actionToken(priorAssistantMessages)
        if (stored == null || token == null || stored.token != token || stored.doctorId != doctor.id) {
            return mapOf(
                "supported" to false,
                "status" to "FAILURE",
                "spokenHint" to "I don't have a cancellation waiting for confirmation. Tell me which visits to cancel.",
            )
        }
        val current = stored.appointmentIds.mapNotNull { id ->
            appointments.findById(id).orElse(null)?.takeIf { it.doctor.id == doctor.id }
        }
        val fingerprints = current.associate { it.id to fingerprint(it) }
        if (fingerprints != stored.fingerprints || current.size != stored.appointmentIds.size) {
            pending.clear(doctor.id)
            return mapOf(
                "supported" to false,
                "status" to "FAILURE",
                "spokenHint" to "The schedule changed, so I did not cancel anything. Ask me again and I will show the updated list.",
            )
        }
        val cancelled = mutableListOf<Map<String, Any?>>()
        val failed = mutableListOf<Map<String, Any?>>()
        val zone = ranges.zoneOf(doctor.timeZone)
        for (appointment in current) {
            val snapshot = row(appointment, zone)
            val didCancel = cancellationService.cancelByDoctorManual(appointment)
            val saved = appointments.findById(appointment.id).orElse(null)
            if (didCancel && saved?.status == Appointment.Status.CANCELLED) {
                cancelled += snapshot + mapOf("status" to "CANCELLED")
            } else {
                failed += snapshot + mapOf("status" to (saved?.status?.name ?: "UNKNOWN"))
            }
        }
        pending.clear(doctor.id)
        val status = when {
            cancelled.isNotEmpty() && failed.isEmpty() -> "SUCCESS"
            cancelled.isNotEmpty() -> "PARTIAL_SUCCESS"
            else -> "FAILURE"
        }
        val hint = when (status) {
            "SUCCESS" -> "Cancelled ${cancelled.size}. The ${if (cancelled.size == 1) "patient was" else "patients were"} notified in the app."
            "PARTIAL_SUCCESS" -> "Cancelled ${cancelled.size}. ${failed.size} could not be cancelled."
            else -> "Nothing was cancelled."
        }
        return mapOf(
            "supported" to true,
            "status" to status,
            "cardType" to "cancel_result",
            "cancelled" to cancelled,
            "failed" to failed,
            "spokenHint" to hint,
        )
    }

    @Transactional
    fun openAppointment(
        doctor: DoctorProfile,
        userText: String,
        patientId: Long?,
        appointmentId: Long?,
        target: String,
        startIfNeeded: Boolean = false,
    ): Map<String, Any?> {
        val zone = ranges.zoneOf(doctor.timeZone)
        val selected = select(doctor, userText, patientId, appointmentId, zone, upcomingOnly = true)
        if (selected is Selection.NeedsClarification) return selected.payload
        if (selected is Selection.Empty) return selected.payload
        var appointment = (selected as Selection.Ready).appointments.first()
        appointment = lifecycle.markNoShowIfDue(appointment)
        if (appointment.status == Appointment.Status.NO_SHOW) {
            return mapOf(
                "supported" to true,
                "status" to "SUCCESS",
                "cardType" to "appointment_intelligence",
                "appointmentId" to appointment.id,
                "spokenHint" to "That visit was more than 24 hours ago and was never opened, so it is a no-show. I did not open the chart.",
            )
        }
        if (startIfNeeded && !lifecycle.isVideo(appointment) &&
            (appointment.status == Appointment.Status.REQUESTED || appointment.status == Appointment.Status.CONFIRMED)
        ) {
            when (val started = lifecycle.start(doctor, appointment.id)) {
                is AppointmentVisitLifecycleService.Outcome.Failed ->
                    return mapOf(
                        "supported" to false,
                        "status" to "FAILURE",
                        "spokenHint" to started.message,
                        "appointment" to started.appointment?.let { lifecycle.openPayloadFields(it, zone) },
                    )
                is AppointmentVisitLifecycleService.Outcome.Ok -> appointment = started.appointment
            }
        }
        return openCard(appointment, zone, target)
    }

    @Transactional
    fun startAppointment(
        doctor: DoctorProfile,
        userText: String,
        patientId: Long?,
        appointmentId: Long?,
    ): Map<String, Any?> =
        openAppointment(doctor, userText, patientId, appointmentId, target = "documentation", startIfNeeded = true)

    @Transactional
    fun completeAppointment(
        doctor: DoctorProfile,
        appointmentId: Long?,
        priorAssistantMessages: List<String>,
    ): Map<String, Any?> {
        val id = appointmentId ?: focusAppointmentId(priorAssistantMessages)
            ?: return mapOf(
                "supported" to false,
                "status" to "AWAITING_CLARIFICATION",
                "spokenHint" to "Which visit should I complete?",
            )
        return when (val result = lifecycle.completeFromIntelligence(doctor, id)) {
            is AppointmentVisitLifecycleService.Outcome.Failed ->
                mapOf("supported" to false, "status" to "FAILURE", "spokenHint" to result.message)
            is AppointmentVisitLifecycleService.Outcome.Ok ->
                mapOf(
                    "supported" to true,
                    "status" to "SUCCESS",
                    "cardType" to "appointment_intelligence",
                    "appointmentId" to result.appointment.id,
                    "appointmentStatus" to result.appointment.status.name,
                    "spokenHint" to (result.message ?: "Visit completed."),
                )
        }
    }

    @Transactional
    fun requestSignature(
        doctor: DoctorProfile,
        appointmentId: Long?,
        priorAssistantMessages: List<String>,
    ): Map<String, Any?> {
        val id = appointmentId ?: focusAppointmentId(priorAssistantMessages)
            ?: return mapOf(
                "supported" to false,
                "status" to "AWAITING_CLARIFICATION",
                "spokenHint" to "Which visit should I request a signature for?",
            )
        return when (val result = lifecycle.requestSignature(doctor, id)) {
            is AppointmentVisitLifecycleService.Outcome.Failed ->
                mapOf("supported" to false, "status" to "FAILURE", "spokenHint" to result.message)
            is AppointmentVisitLifecycleService.Outcome.Ok ->
                mapOf(
                    "supported" to true,
                    "status" to "SUCCESS",
                    "cardType" to "appointment_intelligence",
                    "appointmentId" to result.appointment.id,
                    "signatureRequested" to true,
                    "spokenHint" to (result.message ?: "Signature requested."),
                )
        }
    }

    @Transactional
    fun appendDictation(
        doctor: DoctorProfile,
        userText: String,
        appointmentId: Long?,
        priorAssistantMessages: List<String>,
        readBack: Boolean,
    ): Map<String, Any?> {
        val focus = focusAppointmentId(priorAssistantMessages) ?: appointmentId
        val appointment = focus?.let { appointments.findById(it).orElse(null) }
            ?.takeIf { it.doctor.id == doctor.id }
            ?: return mapOf(
                "supported" to false,
                "status" to "AWAITING_CLARIFICATION",
                "spokenHint" to "Which visit should I document? Open or start the appointment first.",
            )
        val zone = ranges.zoneOf(doctor.timeZone)
        if (readBack || userText.isBlank()) {
            val text = lifecycle.consultationNoteText(doctor.id, appointment.id)
            return openCard(appointment, zone, "documentation") + mapOf(
                "noteBody" to text,
                "saved" to false,
                "spokenHint" to if (text.isEmpty()) {
                    "There is no consultation note for this visit yet."
                } else {
                    "The note says: $text"
                },
            )
        }
        return try {
            val outcome = lifecycle.appendConsultationNote(doctor, appointment.id, userText)
                ?: return mapOf(
                    "supported" to false,
                    "status" to "FAILURE",
                    "spokenHint" to "I could not save that note.",
                )
            openCard(outcome.appointment, zone, "documentation") + mapOf(
                "noteId" to outcome.note.id,
                "saved" to outcome.appended,
                "signatureRequested" to false,
                "spokenHint" to if (outcome.appended) {
                    "I added that to the consultation note. Say when you want to request the patient's signature."
                } else {
                    "That is already in the consultation note."
                },
            )
        } catch (ex: IllegalArgumentException) {
            mapOf(
                "supported" to false,
                "status" to "FAILURE",
                "spokenHint" to (ex.message ?: "I could not save that note."),
            )
        } catch (ex: IllegalStateException) {
            mapOf(
                "supported" to false,
                "status" to "FAILURE",
                "spokenHint" to (ex.message ?: "I could not save that note."),
            )
        }
    }

    private fun openCard(appointment: Appointment, zone: ZoneId, target: String): Map<String, Any?> {
        val fields = lifecycle.openPayloadFields(appointment, zone)
        val whenLabel = row(appointment, zone)["whenLabel"]
        val where = when (target) {
            "documentation" -> "documentation"
            "patient" -> "patient record"
            else -> "appointment"
        }
        return mapOf(
            "supported" to true,
            "status" to "SUCCESS",
            "cardType" to "open_appointment",
            "target" to target,
            "appointmentId" to appointment.id,
            "patientId" to appointment.patient.id,
            "appointment" to (fields + mapOf("whenLabel" to whenLabel)),
            "route" to fields["route"],
            "actionContext" to mapOf(
                "type" to "FOCUS",
                "appointmentId" to appointment.id,
                "patientId" to appointment.patient.id,
            ),
            "spokenHint" to "${fields["patientName"]} · $whenLabel · ${fields["status"]}. Opening the $where.",
        )
    }

    private fun select(
        doctor: DoctorProfile,
        userText: String,
        patientId: Long?,
        appointmentId: Long?,
        zone: ZoneId,
        upcomingOnly: Boolean = false,
    ): Selection {
        if (appointmentId != null) {
            val appointment = appointments.findById(appointmentId).orElse(null)
                ?: return Selection.Empty(notFound())
            if (appointment.doctor.id != doctor.id) return Selection.Empty(notFound())
            return Selection.Ready(listOf(appointment))
        }
        val range = ranges.findRanges(userText, zone).firstOrNull()
        val now = clock.instant()
        if (!upcomingOnly && range == null) {
            return Selection.NeedsClarification(
                mapOf(
                    "supported" to true,
                    "status" to "AWAITING_CLARIFICATION",
                    "spokenHint" to "Which visits should I cancel? Tell me the day, or say all for a period such as next week.",
                ),
            )
        }
        val start = range?.startInclusive
            ?: if (upcomingOnly) now.minus(AppointmentVisitLifecycleService.NO_SHOW_AFTER) else now
        val end = range?.endExclusive ?: now.atZone(zone).plusDays(120).toInstant()
        var rows = appointments.findByDoctorIdAndStartAtBetween(doctor.id, start, end)
        if (patientId != null) rows = rows.filter { it.patient.id == patientId }
        if (upcomingOnly) {
            rows = rows.filter {
                it.status != Appointment.Status.CANCELLED &&
                    it.status != Appointment.Status.COMPLETED &&
                    it.status != Appointment.Status.NO_SHOW &&
                    (
                        it.status == Appointment.Status.IN_PROGRESS ||
                            !it.startAt.isBefore(now.minus(AppointmentVisitLifecycleService.NO_SHOW_AFTER))
                        )
            }
        }
        if (rows.isEmpty()) {
            return Selection.Empty(
                mapOf(
                    "supported" to true,
                    "status" to "SUCCESS",
                    "spokenHint" to "I could not find a matching visit. Nothing was changed.",
                ),
            )
        }
        val wantsAll = Regex("""\b(all|every|whole)\b""", RegexOption.IGNORE_CASE).containsMatchIn(userText) &&
            !Regex("""\bone of\b""", RegexOption.IGNORE_CASE).containsMatchIn(userText)
        if (upcomingOnly) {
            val inProgress = rows.filter { it.status == Appointment.Status.IN_PROGRESS }.minByOrNull { it.startAt }
            val next = inProgress ?: rows.minBy { it.startAt }
            if (rows.size == 1 || patientId == null || userText.contains("next", ignoreCase = true) ||
                userText.contains("start", ignoreCase = true)
            ) {
                return Selection.Ready(listOf(next))
            }
        }
        if (rows.size > 1 && !wantsAll) {
            return Selection.NeedsClarification(
                mapOf(
                    "supported" to true,
                    "status" to "AWAITING_CLARIFICATION",
                    "appointments" to rows.take(8).map { row(it, zone) },
                    "spokenHint" to "I found ${rows.size} visits. Which one should I cancel?",
                ),
            )
        }
        return Selection.Ready(rows.sortedBy { it.startAt })
    }

    private fun ineligibleReason(appointment: Appointment, now: java.time.Instant): String? = when {
        appointment.status == Appointment.Status.CANCELLED -> "Already cancelled"
        appointment.status == Appointment.Status.COMPLETED -> "Already completed"
        appointment.startAt.isBefore(now) -> "The visit time has passed"
        else -> null
    }

    private fun row(appointment: Appointment, zone: ZoneId): Map<String, Any?> {
        val local = appointment.startAt.atZone(zone)
        return mapOf(
            "appointmentId" to appointment.id,
            "patientId" to appointment.patient.id,
            "patientName" to appointment.patient.fullName.ifBlank { "Patient" },
            "whenLabel" to "${local.format(dayFmt)} at ${local.format(timeFmt)}",
            "startAt" to appointment.startAt.toString(),
            "status" to appointment.status.name,
            "location" to appointment.location,
        )
    }

    private fun fingerprint(appointment: Appointment): String =
        "${appointment.startAt}|${appointment.status.name}"

    private fun notFound() = mapOf(
        "supported" to false,
        "status" to "FAILURE",
        "spokenHint" to "I could not find that visit.",
    )

    private sealed class Selection {
        data class Ready(val appointments: List<Appointment>) : Selection()
        data class Empty(val payload: Map<String, Any?>) : Selection()
        data class NeedsClarification(val payload: Map<String, Any?>) : Selection()
    }

    companion object {
        const val MAX_BULK = 40

        fun isExplicitConfirmation(text: String): Boolean {
            val normalized = text.trim().lowercase()
                .replace(Regex("""[,.!?]+"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            return normalized in CONFIRMATIONS
        }

        fun actionToken(messages: List<String>): String? =
            messages.asReversed().firstNotNullOfOrNull { message ->
                ACTION.find(message)?.groupValues?.getOrNull(1)
            }

        fun focusAppointmentId(messages: List<String>): Long? =
            messages.asReversed().firstNotNullOfOrNull { message ->
                FOCUS.find(message)?.groupValues?.getOrNull(1)?.toLongOrNull()
            }

        private val CONFIRMATIONS = setOf(
            "yes",
            "yes cancel",
            "yes cancel them",
            "yes cancel those",
            "confirm",
            "confirm cancellation",
            "go ahead",
            "do it",
            "cancel them",
            "cancel those",
            "proceed",
        )
        private val ACTION = Regex("""<appointment_action>([0-9a-fA-F-]{36})</appointment_action>""")
        private val FOCUS = Regex("""<appointment_focus>(\d+)</appointment_focus>""")
    }
}
