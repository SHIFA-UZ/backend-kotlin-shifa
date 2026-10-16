package com.shifa.service

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
    private val clock: Clock,
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
        val now = clock.instant()
        if (appointment.status != Appointment.Status.REQUESTED &&
            appointment.status != Appointment.Status.CONFIRMED
        ) {
            return appointment
        }
        if (!appointment.startAt.isBefore(now.minus(NO_SHOW_AFTER))) return appointment
        appointment.status = Appointment.Status.NO_SHOW
        return appointments.save(appointment)
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
        val appointment = appointments.findById(appointmentId).orElse(null)
            ?: return Outcome.Failed("I could not find that visit.")
        if (appointment.doctor.id != doctor.id) return Outcome.Failed("I could not find that visit.")
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
        val appointment = appointments.findById(appointmentId).orElse(null)
            ?: return Outcome.Failed("I could not find that visit.")
        if (appointment.doctor.id != doctor.id) return Outcome.Failed("I could not find that visit.")
        if (appointment.status == Appointment.Status.COMPLETED) {
            return Outcome.Ok(appointment, "That visit is already completed.")
        }
        if (appointment.status != Appointment.Status.IN_PROGRESS) {
            return Outcome.Failed("I can only complete a visit that is in progress.")
        }
        appointment.status = Appointment.Status.COMPLETED
        val saved = appointments.save(appointment)
        try {
            treatmentPlanStatusService.maybeAutoCompletePlansForAppointment(appointmentId)
        } catch (_: Exception) {
        }
        try {
            aiDraftNoteService.confirmGeneratedScribeDrafts(
                appointmentId,
                doctor.id,
                ScribePipelineService.SCRIBE_LABEL,
            )
        } catch (e: Exception) {
            log.warn("Could not auto-confirm scribe drafts on intelligence complete for {}: {}", appointmentId, e.message)
        }
        try {
            appointmentDocumentationService.generateForAppointment(appointmentId)
        } catch (e: Exception) {
            log.warn("Could not generate documentation on intelligence complete for {}: {}", appointmentId, e.message)
        }
        try {
            if (!scribePipelineService.isScribeInFlight(appointmentId)) {
                visitSummaryService.enqueueGeneration(appointmentId, appointment.patient.language, force = false)
            }
        } catch (_: Exception) {
        }
        val verified = appointments.findById(appointmentId).orElse(saved)
        return if (verified.status == Appointment.Status.COMPLETED) {
            Outcome.Ok(verified)
        } else {
            Outcome.Failed("I could not verify that the visit was completed.", verified)
        }
    }

    @Transactional
    fun requestSignature(doctor: DoctorProfile, appointmentId: Long): Outcome {
        val appointment = appointments.findById(appointmentId).orElse(null)
            ?: return Outcome.Failed("I could not find that visit.")
        if (appointment.doctor.id != doctor.id) return Outcome.Failed("I could not find that visit.")
        return requestSignature(appointment)
    }

    /** Caller must already enforce clinic or doctor access. */
    @Transactional
    fun requestSignature(appointment: Appointment): Outcome {
        when (appointment.status) {
            Appointment.Status.CANCELLED -> return Outcome.Failed("That visit was cancelled.")
            Appointment.Status.NO_SHOW -> return Outcome.Failed("That visit was marked as a no-show.")
            else -> Unit
        }
        appointment.signatureRequested = true
        val saved = appointments.save(appointment)
        val doctorName =
            "${appointment.doctor.firstName} ${appointment.doctor.lastName}".trim().ifEmpty { "Doctor" }
        val notif = Notification(
            patient = appointment.patient,
            title = "Signature Requested",
            message = "Dr. $doctorName is requesting your signature for the appointment summary.",
            type = Notification.Type.SIGNATURE_REQUESTED,
            appointmentId = appointment.id,
        )
        val savedNotif = notifications.save(notif)
        appointment.patient.fcmToken?.let { fcmService.sendPatientNotification(it, savedNotif) }
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
        val appointment = appointments.findById(appointmentId).orElse(null)
            ?: return null
        if (appointment.doctor.id != doctor.id) return null
        val patientId = appointment.patient.id ?: return null
        when (appointment.status) {
            Appointment.Status.CANCELLED, Appointment.Status.NO_SHOW ->
                throw IllegalStateException("I can't add notes to a cancelled or no-show visit.")
            else -> Unit
        }
        val existing = consultationNotes.findByAppointmentIdOrderByCreatedAtAsc(appointmentId)
            .lastOrNull { it.doctorId == doctor.id }
        if (existing != null) {
            val current = existing.body?.trim().orEmpty()
            val combined = if (current.isEmpty()) addition else current + "\n\n" + addition
            require(combined.length <= MAX_NOTE_CHARS) { "That note is too long." }
            existing.body = combined
            val saved = consultationNotes.save(existing)
            saved.id?.let { clinicalRagIndexingService.reindexConsultationNote(it) }
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
        return NoteOutcome(saved, appointment, appended = true)
    }

    fun consultationNoteText(doctorId: Long, appointmentId: Long): String =
        consultationNotes.findByAppointmentIdOrderByCreatedAtAsc(appointmentId)
            .lastOrNull { it.doctorId == doctorId }
            ?.body
            ?.trim()
            .orEmpty()

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

    companion object {
        val NO_SHOW_AFTER: Duration = Duration.ofHours(24)
        const val MAX_NOTE_CHARS = 20000
    }
}
