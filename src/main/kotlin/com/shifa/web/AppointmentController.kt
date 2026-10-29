// src/main/kotlin/com/shifa/web/AppointmentController.kt
package com.shifa.web

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.domain.Appointment
import com.shifa.domain.Notification
import com.shifa.domain.AiDraftNote
import com.shifa.repo.AiDraftNoteRepository
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.DoctorLocationRepository
import com.shifa.repo.ConsultationNoteRepository
import com.shifa.repo.NotificationRepository
import com.shifa.service.ClinicAccessService
import com.shifa.service.AppointmentCancellationService
import com.shifa.service.AppointmentVisitLifecycleService
import com.shifa.i18n.PatientPaymentPushI18n
import com.shifa.service.FcmService
import com.shifa.service.ClinicalRagIndexingService
import com.shifa.service.SlotAvailabilityService
import com.shifa.repo.TreatmentPlanAppointmentLinkRepository
import com.shifa.repo.TreatmentPlanLineFulfillmentRepository
import com.shifa.service.TreatmentPlanFulfillmentService
import com.shifa.service.VisitChargeService
import com.shifa.service.ScribePipelineService
import com.shifa.service.AppointmentDocumentationService
import com.shifa.service.DoctorAppointmentRescheduleService
import com.shifa.service.DoctorRescheduleCommand
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
    private val doctorLocations: DoctorLocationRepository,
    private val notifications: NotificationRepository,
    private val consultationNoteRepo: ConsultationNoteRepository,
    private val aiDraftNoteRepo: AiDraftNoteRepository,
    private val fcmService: FcmService,
    private val objectMapper: ObjectMapper,
    private val clinicalRagIndexingService: ClinicalRagIndexingService,
    private val clinicAccess: ClinicAccessService,
    private val visitChargeService: VisitChargeService,
    private val fulfillmentService: TreatmentPlanFulfillmentService,
    private val planLinks: TreatmentPlanAppointmentLinkRepository,
    private val lineFulfillments: TreatmentPlanLineFulfillmentRepository,
    private val slotAvailabilityService: SlotAvailabilityService,
    private val appointmentCancellationService: AppointmentCancellationService,
    private val appointmentVisitLifecycleService: AppointmentVisitLifecycleService,
    private val appointmentBriefingService: com.shifa.service.AppointmentBriefingService,
    private val scribePipelineService: ScribePipelineService,
    private val appointmentDocumentationService: AppointmentDocumentationService,
    private val doctorAppointmentRescheduleService: DoctorAppointmentRescheduleService,
    private val patientDocumentService: com.shifa.service.PatientDocumentService,
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
        /** Same live-visit fields as the patient side (ask 13). */
        val alreadySigned: Boolean = false,
        val documentIds: List<Long> = emptyList(),
        val notes: String? = null,
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
            alreadySigned = appointment.patientSignatureImage != null,
            documentIds = patientDocumentService.documentIdsByAppointment(listOf(appointmentId))[appointmentId] ?: emptyList(),
            notes = appointment.reason,
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

    data class PutConsultationNotesRequest(
        val body: String? = null,
    )

    /** Save the doctor's appointment notes without completing the visit. */
    @PutMapping("/{appointmentId}/consultation-notes")
    fun putConsultationNotes(
        @AuthenticationPrincipal principal: Any,
        @PathVariable appointmentId: Long,
        @RequestBody body: PutConsultationNotesRequest,
    ) {
        val appointment = appts.findById(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }
        clinicAccess.assertCanAccessAppointmentResource(principal, appointment.doctor.id)
        val text = body.body?.trim().orEmpty()
        if (text.isEmpty()) return
        try {
            appointmentVisitLifecycleService.upsertManualConsultationNote(
                appointment.doctor,
                appointmentId,
                text,
            )
        } catch (ex: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, ex.message)
        } catch (ex: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, ex.message)
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
        when (val result = appointmentVisitLifecycleService.requestSignature(appointment)) {
            is AppointmentVisitLifecycleService.Outcome.Failed ->
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, result.message)
            is AppointmentVisitLifecycleService.Outcome.Ok -> Unit
        }
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
        val slotMinutes: Int,
        /** Clinic of the free slot. A move can land at a different location than the original visit. */
        val locationId: Long? = null,
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

        when (appointment.status) {
            Appointment.Status.CANCELLED ->
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot complete a cancelled appointment")
            Appointment.Status.NO_SHOW ->
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot complete a no-show appointment")
            else -> Unit
        }

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
        if (!wasAlreadyCompleted && doctorNotes.isNotEmpty() && appointment.patientSignedAt != null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "The patient has already signed this visit. I can't change the consultation note.",
            )
        }
        if (!wasAlreadyCompleted && doctorNotes.isNotEmpty()) {
            try {
                appointmentVisitLifecycleService.upsertManualConsultationNote(
                    appointment.doctor,
                    appointmentId,
                    doctorNotes,
                )
            } catch (ex: IllegalStateException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, ex.message)
            } catch (ex: IllegalArgumentException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, ex.message)
            }
        }

        appointment.status = Appointment.Status.COMPLETED
        appts.save(appointment)

        val followUp = appointmentVisitLifecycleService.applyCompletionFollowUp(
            appointment,
            awaitingScribe = body?.awaitingScribe == true,
        )
        return CompleteAppointmentResponse(
            status = "COMPLETED",
            scribePending = followUp.scribePending,
            hasDocumentation = followUp.hasDocumentation,
            hasScribeNote = followUp.hasScribeNote,
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

        val newStartAt = Instant.parse(req.startAt)
        try {
            doctorAppointmentRescheduleService.reschedule(
                appointment.doctor,
                DoctorRescheduleCommand(
                    appointmentId = appointment.id,
                    expectedStartAt = appointment.startAt,
                    expectedStatus = appointment.status,
                    newStartAt = newStartAt,
                    slotMinutes = req.slotMinutes,
                    locationId = req.locationId,
                ),
            )
        } catch (e: NoSuchElementException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        } catch (e: IllegalStateException) {
            val status = if (e.message?.contains("Overlapping") == true ||
                e.message?.contains("already has an appointment") == true
            ) HttpStatus.CONFLICT else HttpStatus.BAD_REQUEST
            throw ResponseStatusException(status, e.message, e)
        }
    }
}
