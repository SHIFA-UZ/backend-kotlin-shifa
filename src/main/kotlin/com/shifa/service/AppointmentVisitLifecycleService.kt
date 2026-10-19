package com.shifa.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.domain.Appointment
import com.shifa.domain.ConsultationNote
import com.shifa.domain.DoctorProfile
import com.shifa.domain.Notification
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.ConsultationNoteRepository
import com.shifa.repo.NotificationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Start, complete, no-show, signature, and consultation-note append for Intelligence chat
 * and the appointment APIs. Video visits become [Appointment.Status.IN_PROGRESS] when the
 * doctor joins the call; this service starts non-video visits only.
 */
@Service
class AppointmentVisitLifecycleService(
    private val appointments: AppointmentRepository,
    private val consultationNotes: ConsultationNoteRepository,
    private val notifications: NotificationRepository,
    private val fcmService: FcmService,
    private val aiDraftNoteService: AiDraftNoteService,
    private val appointmentDocumentationService: AppointmentDocumentationService,
    private val visitSummaryService: PatientVisitAiSummaryService,
    private val treatmentPlanStatusService: TreatmentPlanStatusService,
    private val clinicalRagIndexingService: ClinicalRagIndexingService,
    private val scribePipelineService: ScribePipelineService,
    private val scribeRecordingService: ScribeRecordingService,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    sealed class Outcome {
        data class Ok(val appointment: Appointment, val message: String? = null) : Outcome()
        data class Failed(val message: String, val appointment: Appointment? = null) : Outcome()
    }

    data class NoteOutcome(
        val note: ConsultationNote,
        val appointment: Appointment,
        val appended: Boolean,
    )

    data class CompletionFollowUp(
        val scribePending: Boolean,
        val hasDocumentation: Boolean,
        val hasScribeNote: Boolean,
    )

    fun isVideo(appointment: Appointment): Boolean =
        appointment.location.lowercase().contains("video")

    fun zoneOf(doctor: DoctorProfile): ZoneId =
        try {
            ZoneId.of(doctor.timeZone.ifBlank { "Asia/Tashkent" })
        } catch (_: Exception) {
            ZoneId.of("Asia/Tashkent")
        }

    /**
     * Never-opened visits more than 24 hours past their start become [Appointment.Status.NO_SHOW].
     * Opened means status is already [IN_PROGRESS] or [COMPLETED].
     */
    @Transactional
    fun markNoShowIfDue(appointment: Appointment): Appointment {
        val current = appointments.findByIdForUpdate(appointment.id).orElse(appointment)
        val now = clock.instant()
        if (current.status != Appointment.Status.REQUESTED &&
            current.status != Appointment.Status.CONFIRMED
        ) {
            return current
        }
        if (!current.startAt.isBefore(now.minus(NO_SHOW_AFTER))) return current
        current.status = Appointment.Status.NO_SHOW
        return appointments.save(current)
    }

    fun canStartNonVideo(appointment: Appointment, doctor: DoctorProfile): String? {
        if (appointment.doctor.id != doctor.id) return "I could not find that visit."
        if (isVideo(appointment)) {
            return "Video visits start when you join the call. I can open that visit for you."
        }
        when (appointment.status) {
            Appointment.Status.IN_PROGRESS -> return null
            Appointment.Status.COMPLETED -> return "That visit is already completed."
            Appointment.Status.CANCELLED -> return "That visit was cancelled."
            Appointment.Status.NO_SHOW -> return "That visit was marked as a no-show."
            Appointment.Status.REQUESTED, Appointment.Status.CONFIRMED -> Unit
            Appointment.Status.PENDING_PAYMENT -> return "That visit is still waiting for payment."
            Appointment.Status.EXPIRED -> return "That visit expired before payment."
            Appointment.Status.PAYMENT_FAILED -> return "Payment failed for that visit."
        }
        val now = clock.instant()
        if (appointment.startAt.isBefore(now.minus(NO_SHOW_AFTER))) {
            return "That visit was more than 24 hours ago and was never opened, so it is a no-show."
        }
        if (!isWithinStartWindow(appointment, doctor, now)) {
            return "I can only start a non-video visit for today or tomorrow, or within 24 hours after its start."
        }
        return null
    }

    @Transactional
    fun start(doctor: DoctorProfile, appointmentId: Long): Outcome {
        val appointment = loadOwned(doctor, appointmentId)
            ?: return Outcome.Failed("I could not find that visit.")
        val refreshed = markNoShowIfDue(appointment)
        if (refreshed.status == Appointment.Status.NO_SHOW) {
            return Outcome.Failed(
                "That visit was more than 24 hours ago and was never opened, so it is a no-show.",
                refreshed,
            )
        }
        if (refreshed.status == Appointment.Status.IN_PROGRESS) {
            return Outcome.Ok(refreshed, "That visit is already in progress.")
        }
        canStartNonVideo(refreshed, doctor)?.let { return Outcome.Failed(it, refreshed) }
        refreshed.status = Appointment.Status.IN_PROGRESS
        val saved = appointments.save(refreshed)
        return Outcome.Ok(saved)
    }

    @Transactional
    fun completeFromIntelligence(doctor: DoctorProfile, appointmentId: Long): Outcome {
        val appointment = loadOwned(doctor, appointmentId)
            ?: return Outcome.Failed("I could not find that visit.")
        if (appointment.status == Appointment.Status.COMPLETED) {
            return Outcome.Ok(appointment, "That visit is already completed.")
        }
        if (appointment.status != Appointment.Status.IN_PROGRESS) {
            return Outcome.Failed("I can only complete a visit that is in progress.")
        }
        appointment.status = Appointment.Status.COMPLETED
        val saved = appointments.save(appointment)
        applyCompletionFollowUp(saved, awaitingScribe = false)
        val verified = appointments.findById(appointmentId).orElse(saved)
        return if (verified.status == Appointment.Status.COMPLETED) {
            Outcome.Ok(verified)
        } else {
            Outcome.Failed("I could not verify that the visit was completed.", verified)
        }
    }

    /**
     * Shared post-complete work for the appointment API and Intelligence.
     * A failure here does not roll the visit back from COMPLETED.
     * Repeat calls can generate another documentation PDF; Intelligence skips this
     * when the visit is already completed.
     */
    @Transactional
    fun applyCompletionFollowUp(appointment: Appointment, awaitingScribe: Boolean): CompletionFollowUp {
        val appointmentId = appointment.id
        try {
            treatmentPlanStatusService.maybeAutoCompletePlansForAppointment(appointmentId)
        } catch (_: Exception) {
        }
        try {
            aiDraftNoteService.confirmGeneratedScribeDrafts(
                appointmentId,
                appointment.doctor.id,
                ScribePipelineService.SCRIBE_LABEL,
            )
        } catch (e: Exception) {
            log.warn("Could not auto-confirm scribe drafts on complete for {}: {}", appointmentId, e.message)
        }

        var scribePending = awaitingScribe
        val roomName = "appointment-$appointmentId"
        var startedScribePipeline = false
        try {
            val recordings = scribeRecordingService.listRecordingsForRoom(roomName)
            if (recordings.isNotEmpty()) {
                val alreadyHasScribe = aiDraftNoteService.hasActiveScribeDraft(
                    appointmentId,
                    ScribePipelineService.SCRIBE_LABEL,
                )
                val finished = recordings.firstOrNull { rec ->
                    rec.status.equals("finished", ignoreCase = true) ||
                        rec.status.equals("ready-to-download", ignoreCase = true)
                }
                val inFlight = scribePipelineService.isScribeInFlight(appointmentId)
                if (finished != null && !alreadyHasScribe && !inFlight) {
                    scribePipelineService.processVideoRecording(roomName, finished.id)
                    startedScribePipeline = true
                }
                if (!alreadyHasScribe) {
                    scribePending = true
                }
            }
        } catch (e: Exception) {
            log.warn("Could not inspect Daily recordings for appointment {}: {}", appointmentId, e.message)
        }

        try {
            appointmentDocumentationService.generateForAppointment(appointmentId)
        } catch (e: Exception) {
            log.warn("Could not generate appointment documentation on complete for {}: {}", appointmentId, e.message)
        }

        val status = appointmentDocumentationService.statusFor(appointmentId)
        if (status.hasScribeNote) {
            scribePending = false
        }
        if (!scribePending && !startedScribePipeline && !scribePipelineService.isScribeInFlight(appointmentId)) {
            try {
                visitSummaryService.enqueueGeneration(appointmentId, appointment.patient.language, force = false)
            } catch (_: Exception) {
            }
        }
        return CompletionFollowUp(
            scribePending = scribePending,
            hasDocumentation = status.hasDocumentation,
            hasScribeNote = status.hasScribeNote,
        )
    }

    @Transactional
    fun requestSignature(doctor: DoctorProfile, appointmentId: Long): Outcome {
        val appointment = loadOwned(doctor, appointmentId)
            ?: return Outcome.Failed("I could not find that visit.")
        return requestSignature(appointment)
    }

    /** Caller must already enforce clinic or doctor access. */
    @Transactional
    fun requestSignature(appointment: Appointment): Outcome {
        val current = appointments.findByIdForUpdate(appointment.id).orElse(appointment)
        when (current.status) {
            Appointment.Status.CANCELLED -> return Outcome.Failed("That visit was cancelled.")
            Appointment.Status.NO_SHOW -> return Outcome.Failed("That visit was marked as a no-show.")
            else -> Unit
        }
        if (current.patientSignedAt != null) {
            return Outcome.Ok(current, "The patient has already signed.")
        }
        if (current.signatureRequested) {
            return Outcome.Ok(current, "A signature was already requested. I did not send another notification.")
        }
        current.signatureRequested = true
        val saved = appointments.save(current)
        val doctorName =
            "${current.doctor.firstName} ${current.doctor.lastName}".trim().ifEmpty { "Doctor" }
        val notif = Notification(
            patient = current.patient,
            title = "Signature Requested",
            message = "Dr. $doctorName is requesting your signature for the appointment summary.",
            type = Notification.Type.SIGNATURE_REQUESTED,
            appointmentId = current.id,
        )
        val savedNotif = notifications.save(notif)
        try {
            current.patient.fcmToken?.let { fcmService.sendPatientNotification(it, savedNotif) }
        } catch (e: Exception) {
            log.warn("Signature request saved but push failed for {}: {}", current.id, e.message)
        }
        return Outcome.Ok(saved, "Signature requested. The patient was notified in the app.")
    }

    /**
     * Appends the doctor's words to one consultation note for this visit.
     * Does not invent clinical structure and does not request a signature.
     */
    @Transactional
    fun appendConsultationNote(
        doctor: DoctorProfile,
        appointmentId: Long,
        text: String,
    ): NoteOutcome? {
        val addition = text.trim()
        if (addition.isEmpty()) return null
        require(addition.length <= MAX_NOTE_CHARS) { "That note is too long." }
        val appointment = loadOwned(doctor, appointmentId) ?: return null
        val patientId = appointment.patient.id ?: return null
        when (appointment.status) {
            Appointment.Status.CANCELLED, Appointment.Status.NO_SHOW ->
                throw IllegalStateException("I can't add notes to a cancelled or no-show visit.")
            else -> Unit
        }
        if (appointment.patientSignedAt != null) {
            throw IllegalStateException("The patient has already signed this visit. I can't change the consultation note.")
        }
        val existing = latestManualNote(doctor.id, appointmentId)
        if (existing != null) {
            val current = existing.body?.trim().orEmpty()
            if (current == addition || current.endsWith("\n\n$addition")) {
                mergeNotesIntoDentalDocumentation(appointment, addition)
                return NoteOutcome(existing, appointment, appended = false)
            }
            val combined = if (current.isEmpty()) addition else current + "\n\n" + addition
            require(combined.length <= MAX_NOTE_CHARS) { "That note is too long." }
            existing.body = combined
            val saved = consultationNotes.save(existing)
            saved.id?.let { clinicalRagIndexingService.reindexConsultationNote(it) }
            mergeNotesIntoDentalDocumentation(appointment, addition)
            return NoteOutcome(saved, appointment, appended = true)
        }
        val saved = consultationNotes.save(
            ConsultationNote(
                doctorId = doctor.id,
                patientId = patientId,
                appointmentId = appointment.id,
                body = addition,
                source = "MANUAL",
            ),
        )
        saved.id?.let { clinicalRagIndexingService.reindexConsultationNote(it) }
        mergeNotesIntoDentalDocumentation(appointment, addition)
        return NoteOutcome(saved, appointment, appended = true)
    }

    /**
     * Replaces the doctor's manual consultation note for this visit so Intelligence
     * dictation and the appointment notes field stay the same document.
     */
    @Transactional
    fun upsertManualConsultationNote(
        doctor: DoctorProfile,
        appointmentId: Long,
        text: String,
    ): NoteOutcome? {
        val body = text.trim()
        if (body.isEmpty()) return null
        require(body.length <= MAX_NOTE_CHARS) { "That note is too long." }
        val appointment = loadOwned(doctor, appointmentId) ?: return null
        val patientId = appointment.patient.id ?: return null
        when (appointment.status) {
            Appointment.Status.CANCELLED, Appointment.Status.NO_SHOW ->
                throw IllegalStateException("I can't add notes to a cancelled or no-show visit.")
            else -> Unit
        }
        if (appointment.patientSignedAt != null) {
            throw IllegalStateException("The patient has already signed this visit. I can't change the consultation note.")
        }
        val existing = latestManualNote(doctor.id, appointmentId)
        if (existing != null) {
            if (existing.body?.trim() == body) {
                return NoteOutcome(existing, appointment, appended = false)
            }
            existing.body = body
            val saved = consultationNotes.save(existing)
            saved.id?.let { clinicalRagIndexingService.reindexConsultationNote(it) }
            return NoteOutcome(saved, appointment, appended = true)
        }
        val saved = consultationNotes.save(
            ConsultationNote(
                doctorId = doctor.id,
                patientId = patientId,
                appointmentId = appointment.id,
                body = body,
                source = "MANUAL",
            ),
        )
        saved.id?.let { clinicalRagIndexingService.reindexConsultationNote(it) }
        return NoteOutcome(saved, appointment, appended = true)
    }

    fun consultationNoteText(doctorId: Long, appointmentId: Long): String =
        consultationNotes.findByAppointmentIdOrderByCreatedAtAsc(appointmentId)
            .lastOrNull { it.doctorId == doctorId }
            ?.body
            ?.trim()
            .orEmpty()

    private fun latestManualNote(doctorId: Long, appointmentId: Long): ConsultationNote? =
        consultationNotes.findByAppointmentIdOrderByCreatedAtAsc(appointmentId)
            .lastOrNull { it.doctorId == doctorId && !it.source.equals("AI_DRAFT", ignoreCase = true) }

    /** Copy Intelligence notes into dental-chart notes so both editors show the same text. */
    private fun mergeNotesIntoDentalDocumentation(appointment: Appointment, text: String) {
        val addition = text.trim()
        if (addition.isEmpty()) return
        val raw = appointment.dentalDocumentation?.trim().orEmpty()
        val root: MutableMap<String, Any?> = if (raw.isEmpty()) {
            linkedMapOf(
                "version" to 2,
                "dentition" to "permanent",
                "teeth" to emptyMap<String, Any>(),
                "discountPercent" to 0,
                "notes" to addition,
            )
        } else {
            try {
                objectMapper.readValue(raw, object : TypeReference<MutableMap<String, Any?>>() {})
            } catch (_: Exception) {
                log.warn("Could not merge Intelligence notes into dental documentation for {}", appointment.id)
                return
            }
        }
        val current = root["notes"]?.toString()?.trim().orEmpty()
        val merged = when {
            current.isEmpty() -> addition
            current.contains(addition) -> current
            addition.contains(current) -> addition
            else -> "$current\n\n$addition"
        }
        if (merged == current && raw.isNotEmpty()) return
        root["notes"] = merged
        appointment.dentalDocumentation = objectMapper.writeValueAsString(root)
        appointments.save(appointment)
        clinicalRagIndexingService.reindexAppointmentDentalDocumentation(appointment.id)
    }

    fun openPayloadFields(appointment: Appointment, zone: ZoneId): Map<String, Any?> {
        val localStart = appointment.startAt.atZone(zone)
        val localEnd = appointment.endAt.atZone(zone)
        val video = isVideo(appointment)
        return mapOf(
            "appointmentId" to appointment.id,
            "patientId" to appointment.patient.id,
            "patientName" to appointment.patient.fullName.ifBlank { "Patient" },
            "location" to appointment.location,
            "status" to appointment.status.name,
            "startAt" to appointment.startAt.toString(),
            "endAt" to appointment.endAt.toString(),
            "day" to localStart.toLocalDate().toString(),
            "startLocal" to "%02d:%02d".format(localStart.hour, localStart.minute),
            "endLocal" to "%02d:%02d".format(localEnd.hour, localEnd.minute),
            "reason" to appointment.reason,
            "isVideo" to video,
            "route" to if (video) "video" else "inPerson",
            "signatureRequested" to appointment.signatureRequested,
        )
    }

    private fun isWithinStartWindow(appointment: Appointment, doctor: DoctorProfile, now: Instant): Boolean {
        if (!appointment.startAt.isBefore(now.minus(NO_SHOW_AFTER)) &&
            !appointment.startAt.isAfter(now)
        ) {
            return true
        }
        val zone = zoneOf(doctor)
        val startDay = appointment.startAt.atZone(zone).toLocalDate()
        val today = LocalDate.now(clock.withZone(zone))
        return startDay == today || startDay == today.plusDays(1)
    }

    private fun loadOwned(doctor: DoctorProfile, appointmentId: Long): Appointment? {
        val appointment = appointments.findByIdForUpdate(appointmentId).orElse(null) ?: return null
        if (appointment.doctor.id != doctor.id) return null
        return appointment
    }

    companion object {
        val NO_SHOW_AFTER: Duration = Duration.ofHours(24)
        const val MAX_NOTE_CHARS = 20000
    }
}
