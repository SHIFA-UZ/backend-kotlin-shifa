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
import com.shifa.service.ClinicAccessService
import com.shifa.service.AppointmentCancellationService
import com.shifa.i18n.PatientPaymentPushI18n
import com.shifa.service.FcmService
import com.shifa.service.ClinicalRagIndexingService
import com.shifa.service.PatientVisitAiSummaryService
import com.shifa.service.SlotAvailabilityService
import com.shifa.service.TreatmentPlanStatusService
import com.shifa.repo.TreatmentPlanAppointmentLinkRepository
import com.shifa.repo.TreatmentPlanLineFulfillmentRepository
import com.shifa.service.TreatmentPlanFulfillmentService
import com.shifa.service.VisitChargeService
import com.shifa.service.ScribePipelineService
import com.shifa.service.ScribeRecordingService
import com.shifa.service.AiDraftNoteService
import com.shifa.service.AppointmentDocumentationService
import com.shifa.domain.ConsultationNote
import org.slf4j.LoggerFactory
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
    private val visitSummaryService: PatientVisitAiSummaryService,
    private val clinicalRagIndexingService: ClinicalRagIndexingService,
    private val clinicAccess: ClinicAccessService,
    private val visitChargeService: VisitChargeService,
    private val fulfillmentService: TreatmentPlanFulfillmentService,
    private val planLinks: TreatmentPlanAppointmentLinkRepository,
    private val lineFulfillments: TreatmentPlanLineFulfillmentRepository,
    private val treatmentPlanStatusService: TreatmentPlanStatusService,
    private val slotAvailabilityService: SlotAvailabilityService,
    private val appointmentCancellationService: AppointmentCancellationService,
    private val appointmentBriefingService: com.shifa.service.AppointmentBriefingService,
    private val scribePipelineService: ScribePipelineService,
    private val scribeRecordingService: ScribeRecordingService,
    private val aiDraftNoteService: AiDraftNoteService,
    private val appointmentDocumentationService: AppointmentDocumentationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

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
        val patientSignatureImageBase64: String?,
        val linkedPlanId: Long? = null,
        val linkedPlanTitle: String? = null,
        val fulfilledLineIds: List<Long> = emptyList(),
        val briefingStatus: String? = null,
        val attachmentCount: Int = 0,
    )

    @GetMapping("/{appointmentId}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun getById(
        @AuthenticationPrincipal principal: Any,
        @PathVariable appointmentId: Long
    ): AppointmentDto {
        val appointment = appts.findById(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }
        clinicAccess.assertCanAccessAppointmentResource(principal, appointment.doctor.id)
        val planRow = planLinks.findPlanIdAndTitleByAppointmentId(appointmentId).firstOrNull()
        val fulfilled = lineFulfillments.findLineIdsByAppointmentId(appointmentId)
        val briefingStatus = appointmentBriefingService.summaryStatus(appointmentId)
        val attachmentCount = appointmentBriefingService
            .summariesForAppointments(listOf(appointmentId))[appointmentId]
            ?.second
            ?: 0
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
            patientSignatureImageBase64 = appointment.patientSignatureImage,
            linkedPlanId = (planRow?.get(0) as? Number)?.toLong(),
            linkedPlanTitle = planRow?.get(1) as? String,
            fulfilledLineIds = fulfilled,
            briefingStatus = briefingStatus,
            attachmentCount = attachmentCount,
        )
    }

    @GetMapping("/{appointmentId}/briefing")
    fun getBriefing(
        @AuthenticationPrincipal principal: Any,
        @PathVariable appointmentId: Long,
    ): com.shifa.service.AppointmentBriefingDto {
        val appointment = appts.findById(appointmentId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found") }
        clinicAccess.assertCanAccessAppointmentResource(principal, appointment.doctor.id)
        return appointmentBriefingService.getForAppointment(appointmentId)
    }

    @PostMapping("/{appointmentId}/briefing/retry")
    fun retryBriefing(
        @AuthenticationPrincipal principal: Any,
        @PathVariable appointmentId: Long,
    ): com.shifa.service.AppointmentBriefingDto {
        val appointment = appts.findById(appointmentId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found") }
        clinicAccess.assertCanAccessAppointmentResource(principal, appointment.doctor.id)
        return appointmentBriefingService.retryForAppointment(appointmentId)
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
        @AuthenticationPrincipal principal: Any,
        @PathVariable appointmentId: Long
    ): List<ConsultationNoteDto> {
        val appointment = appts.findById(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }
        clinicAccess.assertCanAccessAppointmentResource(principal, appointment.doctor.id)
        val pendingScribe = aiDraftNoteRepo.findByConsultationIdAndStatusOrderByCreatedAtDesc(
            appointmentId,
            AiDraftNote.Status.GENERATED,
        ).any { it.aiLabel == ScribePipelineService.SCRIBE_LABEL }
        if (pendingScribe) {
            try {
                scribePipelineService.finalizeScribeForAppointment(appointmentId, appointment.doctor.id)
            } catch (e: Exception) {
                log.warn("Could not finalize AI scribe before listing notes for {}: {}", appointmentId, e.message)
            }
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
        @AuthenticationPrincipal principal: Any,
        @PathVariable appointmentId: Long
    ): Map<String, Any?> {
        val appointment = appts.findById(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }
        clinicAccess.assertCanAccessAppointmentResource(principal, appointment.doctor.id)
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
        @AuthenticationPrincipal principal: Any,
        @PathVariable appointmentId: Long,
        @RequestBody body: Map<String, Any?>
    ) {
        val appointment = appts.findById(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }
        clinicAccess.assertCanAccessAppointmentResource(principal, appointment.doctor.id)
        appointment.dentalDocumentation = objectMapper.writeValueAsString(body)
        appts.save(appointment)
        clinicalRagIndexingService.reindexAppointmentDentalDocumentation(appointmentId)
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
        @AuthenticationPrincipal principal: Any,
        @PathVariable appointmentId: Long
    ): List<DraftNoteDto> {
        val appointment = appts.findById(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }
        clinicAccess.assertCanAccessAppointmentResource(principal, appointment.doctor.id)
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
        @AuthenticationPrincipal principal: Any,
        @PathVariable appointmentId: Long
    ) {
        val appointment = appts.findById(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }
        clinicAccess.assertCanAccessAppointmentResource(principal, appointment.doctor.id)
        appointment.signatureRequested = true
        appts.save(appointment)

        val doctorName =
            "${appointment.doctor.firstName ?: ""} ${appointment.doctor.lastName ?: ""}".trim().ifEmpty { "Doctor" }
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
        @AuthenticationPrincipal principal: Any,
        @PathVariable appointmentId: Long
    ) {
        val appointment = appts.findById(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }
        clinicAccess.assertCanAccessAppointmentResource(principal, appointment.doctor.id)
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
        val doctorName =
            "${appointment.doctor.firstName ?: ""} ${appointment.doctor.lastName ?: ""}".trim()
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
        @AuthenticationPrincipal principal: Any,
        @PathVariable appointmentId: Long
    ) {
        val appointment = appts.findById(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }

        clinicAccess.assertCanAccessAppointmentResource(principal, appointment.doctor.id)

        // Past appointments cannot be cancelled
        if (appointment.startAt.isBefore(Instant.now())) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot cancel a past appointment")
        }

        if (!appointmentCancellationService.cancelByDoctorManual(appointment)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Appointment cannot be cancelled")
        }
    }

    data class CompleteAppointmentRequest(
        val clinicId: Long? = null,
        val visitCharges: List<VisitChargeLineReq>? = null,
        val doctorNotes: String? = null,
        val awaitingScribe: Boolean? = null,
    )

    data class VisitChargeLineReq(
        val catalogItemId: Long,
        val quantity: Int = 1,
        val unitPriceMinor: Long? = null,
    )

    data class CompleteAppointmentResponse(
        val status: String,
        val scribePending: Boolean,
        val hasDocumentation: Boolean,
        val hasScribeNote: Boolean,
    )

    data class ScribeStatusResponse(
        val status: String,
        val hasDocumentation: Boolean,
        val hasScribeNote: Boolean,
    )

    @GetMapping("/{appointmentId}/scribe-status")
    fun getScribeStatus(
        @AuthenticationPrincipal principal: Any,
        @PathVariable appointmentId: Long
    ): ScribeStatusResponse {
        val appointment = appts.findById(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }
        clinicAccess.assertCanAccessAppointmentResource(principal, appointment.doctor.id)
        val status = appointmentDocumentationService.statusFor(appointmentId)
        return ScribeStatusResponse(
            status = status.status,
            hasDocumentation = status.hasDocumentation,
            hasScribeNote = status.hasScribeNote,
        )
    }

    @PutMapping("/{appointmentId}/complete")
    fun complete(
        @AuthenticationPrincipal principal: Any,
        @PathVariable appointmentId: Long,
        @RequestBody(required = false) body: CompleteAppointmentRequest?,
    ): CompleteAppointmentResponse {
        val appointment = appts.findById(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }

        clinicAccess.assertCanAccessAppointmentResource(principal, appointment.doctor.id)

        val charges = body?.visitCharges
        val cid = body?.clinicId
        if (!charges.isNullOrEmpty() && cid != null) {
            visitChargeService.addVisitCharges(
                principal,
                cid,
                appointmentId,
                charges.map { VisitChargeService.VisitChargeLine(it.catalogItemId, it.quantity, it.unitPriceMinor) },
            )
        }

        // Auto-derive a VISIT treatment plan from the saved dental documentation
        // (per-teeth priced services) so finance sees the appointment as an unpaid
        // ledger entry without prompting the doctor for charges again. Safe to
        // call even when there is no dental documentation — the service no-ops.
        val wasAlreadyCompleted = appointment.status == Appointment.Status.COMPLETED
        if (!wasAlreadyCompleted && cid != null && charges.isNullOrEmpty()) {
            val skipAutoVisit =
                fulfillmentService.isAppointmentLinkedToComprehensivePlan(appointmentId)
            if (!skipAutoVisit) {
                try {
                    visitChargeService.addDentalChargesFromDocumentation(principal, cid, appointmentId)
                } catch (e: Exception) {
                    // Never block appointment completion because of finance auto-derivation.
                }
            }
        }

        val doctorNotes = body?.doctorNotes?.trim().orEmpty()
        if (!wasAlreadyCompleted && doctorNotes.isNotEmpty()) {
            val patientId = appointment.patient.id
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Appointment has no patient")
            val savedNote = consultationNoteRepo.save(
                ConsultationNote(
                    doctorId = appointment.doctor.id,
                    patientId = patientId,
                    appointmentId = appointment.id,
                    body = doctorNotes,
                    source = "MANUAL",
                )
            )
            savedNote.id?.let { clinicalRagIndexingService.reindexConsultationNote(it) }
        }

        appointment.status = Appointment.Status.COMPLETED
        appts.save(appointment)

        // If every non-cancelled line on any linked treatment plan is complete,
        // promote that plan to COMPLETED. The service runs in its own
        // REQUIRES_NEW transaction and swallows errors, so it cannot block or
        // roll back this completion call.
        try {
            treatmentPlanStatusService.maybeAutoCompletePlansForAppointment(appointmentId)
        } catch (_: Exception) {
            // Best-effort: appointment completion must not fail because the
            // plan-status follow-up failed.
        }

        var scribePending = body?.awaitingScribe == true
        try {
            aiDraftNoteService.confirmGeneratedScribeDrafts(
                appointmentId,
                appointment.doctor.id,
                ScribePipelineService.SCRIBE_LABEL,
            )
        } catch (e: Exception) {
            log.warn("Could not auto-confirm scribe drafts on complete for {}: {}", appointmentId, e.message)
        }

        val roomName = "appointment-$appointmentId"
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
                if (finished != null && !alreadyHasScribe) {
                    scribePipelineService.processVideoRecording(roomName, finished.id)
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

        try {
            visitSummaryService.enqueueGeneration(appointmentId, appointment.patient.language, force = false)
        } catch (_: Exception) {
            // Never fail appointment completion due to AI summary generation issues.
        }

        val status = appointmentDocumentationService.statusFor(appointmentId)
        if (status.hasScribeNote) {
            scribePending = false
        }
        return CompleteAppointmentResponse(
            status = "COMPLETED",
            scribePending = scribePending,
            hasDocumentation = status.hasDocumentation,
            hasScribeNote = status.hasScribeNote,
        )
    }

    @PutMapping("/{appointmentId}/change")
    fun changeSlot(
        @AuthenticationPrincipal principal: Any,
        @PathVariable appointmentId: Long,
        @RequestBody req: ChangeSlotReq
    ) {
        val appointment = appts.findById(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }

        clinicAccess.assertCanAccessAppointmentResource(principal, appointment.doctor.id)

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
        val zone = ZoneId.of(appointment.doctor.timeZone)

        // Check for overlaps for the doctor (excluding the current appointment)
        val overlapping = appts.findOverlapping(appointment.doctor.id!!, newStartAt, newEndAt)
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

        slotAvailabilityService.assertBookableConsecutiveRange(
            doctor = appointment.doctor,
            startAt = newStartAt,
            endAt = newEndAt,
            filterLocationId = appointment.locationRef?.id,
            excludeAppointmentId = appointmentId
        )

        val oldStartLdt = appointment.startAt.atZone(zone).toLocalDateTime()
        val newStartLdt = newStartAt.atZone(zone).toLocalDateTime()
        val startChanged = appointment.startAt != newStartAt
        appointment.startAt = newStartAt
        appointment.endAt = newEndAt
        if (startChanged) {
            appointment.smsReminderSentAt = null
        }
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
