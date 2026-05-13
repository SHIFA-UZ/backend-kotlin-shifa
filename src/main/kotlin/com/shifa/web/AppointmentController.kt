// src/main/kotlin/com/shifa/web/AppointmentController.kt
package com.shifa.web

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.domain.Appointment
import com.shifa.domain.Notification
import com.shifa.domain.AiDraftNote
import com.shifa.repo.AiDraftNoteRepository
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.ConsultationNoteRepository
import com.shifa.repo.NotificationRepository
import com.shifa.security.DoctorPrincipal
import com.shifa.i18n.PatientPaymentPushI18n
import com.shifa.service.FcmService
import com.shifa.service.PatientVisitAiSummaryService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.ZoneId
import java.time.*

@RestController
@RequestMapping("/api/appointments")
class AppointmentController(
    private val appts: AppointmentRepository,
    private val notifications: NotificationRepository,
    private val consultationNoteRepo: ConsultationNoteRepository,
    private val aiDraftNoteRepo: AiDraftNoteRepository,
    private val fcmService: FcmService,
    private val objectMapper: ObjectMapper,
    private val visitSummaryService: PatientVisitAiSummaryService
) {

    // -------------------- Doctor: get single appointment (for polling signature status) --------------------

    data class AppointmentDto(
        val id: Long,
        val patientId: Long?,
        val patientName: String?,
        val doctorId: Long?,
        val startAt: String,
        val endAt: String,
        val location: String,
        val status: String,
        val paymentStatus: String,
        val paymentAmountMinor: Long?,
        val paymentCurrency: String?,
        val signatureRequested: Boolean,
        val patientSignedAt: String?,
        val patientSignatureImageBase64: String?
    )

    @GetMapping("/{appointmentId}")
    fun getById(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable appointmentId: Long
    ): AppointmentDto {
        val doctor = principal.profile
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val appointment = appts.findById(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }
        if (appointment.doctor.id != doctor.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Appointment does not belong to this doctor")
        }
        return AppointmentDto(
            id = appointment.id,
            patientId = appointment.patient?.id,
            patientName = appointment.patient?.fullName,
            doctorId = appointment.doctor.id,
            startAt = appointment.startAt.toString(),
            endAt = appointment.endAt.toString(),
            location = appointment.location,
            status = appointment.status.name,
            paymentStatus = appointment.paymentStatus.name,
            paymentAmountMinor = appointment.paymentAmountMinor,
            paymentCurrency = appointment.paymentCurrency,
            signatureRequested = appointment.signatureRequested,
            patientSignedAt = appointment.patientSignedAt?.toString(),
            patientSignatureImageBase64 = appointment.patientSignatureImage
        )
    }

    // -------------------- Doctor: request patient signature --------------------

    /** Consultation notes for this appointment (e.g. saved Shifa AI drafts). Shown in appointment documentation with "From Shifa AI" badge when source == AI_DRAFT. */
    data class ConsultationNoteDto(
        val id: Long,
        val body: String?,
        val subjective: String?,
        val assessment: String?,
        val plan: String?,
        val source: String,
        val createdAt: String
    )

    @GetMapping("/{appointmentId}/consultation-notes")
    fun getConsultationNotes(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable appointmentId: Long
    ): List<ConsultationNoteDto> {
        val doctor = principal.profile
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val appointment = appts.findById(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }
        if (appointment.doctor.id != doctor.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Appointment does not belong to this doctor")
        }
        val notes = consultationNoteRepo.findByAppointmentIdOrderByCreatedAtAsc(appointmentId)
        return notes.map { n ->
            ConsultationNoteDto(
                id = n.id!!,
                body = n.body,
                subjective = n.subjective,
                assessment = n.assessment,
                plan = n.plan,
                source = n.source,
                createdAt = n.createdAt.toString()
            )
        }
    }

    /**
     * Dental specialty visit documentation (teeth × services, discount, notes) as JSON.
     * GET returns an empty object when nothing was saved yet.
     */
    @GetMapping("/{appointmentId}/dental-documentation")
    fun getDentalDocumentation(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable appointmentId: Long
    ): Map<String, Any?> {
        val doctor = principal.profile
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val appointment = appts.findById(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }
        if (appointment.doctor.id != doctor.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Appointment does not belong to this doctor")
        }
        val raw = appointment.dentalDocumentation?.trim().orEmpty()
        if (raw.isEmpty()) return emptyMap()
        return try {
            objectMapper.readValue(raw, object : TypeReference<MutableMap<String, Any?>>() {})
        } catch (_: Exception) {
            emptyMap()
        }
    }

    @PutMapping("/{appointmentId}/dental-documentation")
    fun putDentalDocumentation(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable appointmentId: Long,
        @RequestBody body: Map<String, Any?>
    ) {
        val doctor = principal.profile
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val appointment = appts.findById(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }
        if (appointment.doctor.id != doctor.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Appointment does not belong to this doctor")
        }
        appointment.dentalDocumentation = objectMapper.writeValueAsString(body)
        appts.save(appointment)
    }

    /** Pending AI draft notes for this appointment (e.g. AI Scribe). Doctor can confirm or discard from the appointment screen. */
    data class IcdSuggestionDto(
        val code: String,
        val title: String,
        val confidence: Double? = null,
        val isTop: Boolean = false
    )

    data class DraftNoteDto(
        val id: String,
        val aiLabel: String,
        val body: String,
        val createdAt: String,
        val icdSuggestions: List<IcdSuggestionDto> = emptyList()
    )

    @GetMapping("/{appointmentId}/draft-notes")
    fun getDraftNotes(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable appointmentId: Long
    ): List<DraftNoteDto> {
        val doctor = principal.profile
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val appointment = appts.findById(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }
        if (appointment.doctor.id != doctor.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Appointment does not belong to this doctor")
        }
        val drafts = aiDraftNoteRepo.findByConsultationIdAndStatusOrderByCreatedAtDesc(
            appointmentId,
            AiDraftNote.Status.GENERATED
        )
        return drafts.map { d ->
            val suggestions = try {
                val raw = d.icdSuggestionsJson
                if (raw.isNullOrBlank()) emptyList()
                else {
                    val node = objectMapper.readTree(raw)
                    if (!node.isArray) emptyList()
                    else node.mapNotNull { n ->
                        val code = n.path("code").asText("").trim()
                        val title = n.path("title").asText("").trim()
                        if (code.isBlank() || title.isBlank()) null
                        else IcdSuggestionDto(
                            code = code,
                            title = title,
                            confidence = n.path("confidence").takeIf { !it.isMissingNode && !it.isNull }?.asDouble(),
                            isTop = n.path("isTop").asBoolean(false)
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
            DraftNoteDto(
                id = d.id.toString(),
                aiLabel = d.aiLabel,
                body = d.aiResponseText,
                createdAt = d.createdAt.toString(),
                icdSuggestions = suggestions
            )
        }
    }

    @PutMapping("/{appointmentId}/request-signature")
    fun requestSignature(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable appointmentId: Long
    ) {
        val doctor = principal.profile
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val appointment = appts.findById(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }
        if (appointment.doctor.id != doctor.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Appointment does not belong to this doctor")
        }
        appointment.signatureRequested = true
        appts.save(appointment)

        val doctorName = "${doctor.firstName ?: ""} ${doctor.lastName ?: ""}".trim().ifEmpty { "Doctor" }
        val notif = Notification(
            patient = appointment.patient,
            title = "Signature Requested",
            message = "Dr. $doctorName is requesting your signature for the appointment summary.",
            type = Notification.Type.SIGNATURE_REQUESTED,
            appointmentId = appointment.id
        )
        val savedNotif = notifications.save(notif)
        appointment.patient.fcmToken?.let { fcmService.sendPatientNotification(it, savedNotif) }
    }

    /**
     * Doctor nudges the patient to pay for a booked video consultation (payment still pending).
     * Sends in-app notification + FCM; patient tap opens checkout for this appointment.
     */
    @PostMapping("/{appointmentId}/notify-payment-reminder")
    fun notifyPaymentReminder(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable appointmentId: Long
    ) {
        val doctor = principal.profile
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val appointment = appts.findById(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }
        if (appointment.doctor.id != doctor.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Appointment does not belong to this doctor")
        }
        if (appointment.status == Appointment.Status.CANCELLED ||
            appointment.status == Appointment.Status.COMPLETED
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot send payment reminder for this appointment")
        }
        if (appointment.paymentStatus != Appointment.PaymentStatus.PENDING) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment is not pending for this appointment")
        }
        val loc = appointment.location.lowercase()
        val isVideo = loc.contains("video")
        if (!isVideo) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Payment reminders are only supported for video consultations"
            )
        }
        val doctorName = "${doctor.firstName ?: ""} ${doctor.lastName ?: ""}".trim()
        val lang = appointment.patient.language
        val notif = Notification(
            patient = appointment.patient,
            title = PatientPaymentPushI18n.paymentTitle(lang),
            message = PatientPaymentPushI18n.doctorNudgeBody(lang, doctorName),
            type = Notification.Type.CONSULTATION_PAYMENT_REMINDER,
            appointmentId = appointment.id
        )
        val savedNotif = notifications.save(notif)
        appointment.patient.fcmToken?.let {
            fcmService.sendPatientNotification(
                it,
                savedNotif,
                mapOf("route" to "/bookings/${appointment.id}/pay")
            )
        }
    }

    /** startAt: ISO 8601 UTC. */
    data class ChangeSlotReq(
        val startAt: String,
        val slotMinutes: Int
    )

    @DeleteMapping("/{appointmentId}")
    fun cancel(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable appointmentId: Long
    ) {
        val doctor = principal.profile
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

        val appointment = appts.findById(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }

        // Verify the appointment belongs to this doctor
        if (appointment.doctor.id != doctor.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Appointment does not belong to this doctor")
        }

        // Past appointments cannot be cancelled
        if (appointment.startAt.isBefore(Instant.now())) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot cancel a past appointment")
        }

        // Cancel the appointment
        appointment.status = Appointment.Status.CANCELLED
        appts.save(appointment)

        val zone = ZoneId.of(doctor.timeZone)
        val startLdt = appointment.startAt.atZone(zone).toLocalDateTime()
        val monthName = startLdt.month.name.lowercase().replaceFirstChar { it.uppercase() }
        val dateStr = "${startLdt.dayOfMonth} $monthName ${startLdt.year}"
        
        val notif = com.shifa.domain.Notification(
            patient = appointment.patient,
            title = "Appointment Cancelled",
            message = "Doctor has cancelled your appointment on $dateStr. Please make another appointment.",
            type = com.shifa.domain.Notification.Type.APPOINTMENT_CANCELLED,
            appointmentId = appointment.id
        )
        val savedNotif = notifications.save(notif)
        appointment.patient.fcmToken?.let { fcmService.sendPatientNotification(it, savedNotif) }
    }

    @PutMapping("/{appointmentId}/complete")
    fun complete(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable appointmentId: Long
    ) {
        val doctor = principal.profile
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

        val appointment = appts.findById(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }

        // Verify the appointment belongs to this doctor
        if (appointment.doctor.id != doctor.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Appointment does not belong to this doctor")
        }

        // Mark appointment as completed
        appointment.status = Appointment.Status.COMPLETED
        appts.save(appointment)
        try {
            visitSummaryService.enqueueGeneration(appointmentId, appointment.patient.language, force = false)
        } catch (_: Exception) {
            // Never fail appointment completion due to AI summary generation issues.
        }
    }

    @PutMapping("/{appointmentId}/change")
    fun changeSlot(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable appointmentId: Long,
        @RequestBody req: ChangeSlotReq
    ) {
        val doctor = principal.profile
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

        val appointment = appts.findById(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }

        // Verify the appointment belongs to this doctor
        if (appointment.doctor.id != doctor.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Appointment does not belong to this doctor")
        }

        // Past appointments cannot be changed
        if (appointment.startAt.isBefore(Instant.now())) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot change a past appointment")
        }

        val newStartAt = Instant.parse(req.startAt)
        // Cannot move appointment to a past date or time
        if (newStartAt.isBefore(Instant.now())) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot move appointment to a past date or time")
        }
        val newEndAt = newStartAt.plusSeconds(req.slotMinutes * 60L)
        val zone = ZoneId.of(doctor.timeZone)

        // Check for overlaps for the doctor (excluding the current appointment)
        val overlapping = appts.findOverlapping(doctor.id!!, newStartAt, newEndAt)
            .filter { it.id != appointmentId }
        
        if (overlapping.isNotEmpty()) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Overlapping appointment exists")
        }

        // Check for overlapping appointments for the patient (prevent double booking)
        val patientOverlapping = appts.findOverlappingForPatient(
            appointment.patient?.id ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found"),
            newStartAt,
            newEndAt
        ).filter { it.id != appointmentId }

        if (patientOverlapping.isNotEmpty()) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Patient already has an appointment scheduled at this date and time. Please choose a different time slot."
            )
        }

        val oldStartLdt = appointment.startAt.atZone(zone).toLocalDateTime()
        val newStartLdt = newStartAt.atZone(zone).toLocalDateTime()
        appointment.startAt = newStartAt
        appointment.endAt = newEndAt
        appts.save(appointment)

        val oldMonthName = oldStartLdt.month.name.lowercase().replaceFirstChar { it.uppercase() }
        val newMonthName = newStartLdt.month.name.lowercase().replaceFirstChar { it.uppercase() }
        val oldDateStr = "${oldStartLdt.dayOfMonth} $oldMonthName ${oldStartLdt.year}"
        val newDateStr = "${newStartLdt.dayOfMonth} $newMonthName ${newStartLdt.year}"
        
        val notif = com.shifa.domain.Notification(
            patient = appointment.patient,
            title = "Appointment Changed",
            message = "Doctor changed your appointment from $oldDateStr to $newDateStr. If you are okay with this appointment, that's good, otherwise reschedule yourself.",
            type = com.shifa.domain.Notification.Type.APPOINTMENT_CHANGED,
            appointmentId = appointment.id
        )
        val savedNotif = notifications.save(notif)
        appointment.patient.fcmToken?.let { fcmService.sendPatientNotification(it, savedNotif) }
    }
}
