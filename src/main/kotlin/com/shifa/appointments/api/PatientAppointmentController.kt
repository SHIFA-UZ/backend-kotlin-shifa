// src/main/kotlin/com/shifa/appointments/api/PatientAppointmentController.kt
package com.shifa.appointments.api

import com.shifa.appointments.api.dto.AppointmentReviewDto
import com.shifa.appointments.api.dto.GenerateVisitSummaryRequest
import com.shifa.appointments.api.dto.AskVisitSummaryRequest

import com.shifa.doctors.infrastructure.DoctorReviewRepository
import com.shifa.appointments.domain.BookingPaymentPolicy
import com.shifa.shared.security.PatientPrincipal
import com.shifa.ai.application.PatientVisitAiSummaryService
import com.shifa.patients.application.PatientProfileMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@RestController
@RequestMapping("/api/patients/me/appointments")
class PatientAppointmentController(
    private val appts: com.shifa.appointments.application.Appointments,
    private val profileMapper: PatientProfileMapper,
    private val reviewRepository: DoctorReviewRepository,
    private val visitSummaryService: PatientVisitAiSummaryService,
    private val documents: com.shifa.documents.application.PatientDocumentService,
    private val currentPatient: com.shifa.patients.application.CurrentPatientResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Single appointment for patient (details + signing). Includes signature + doctor display fields for details screen. */
    data class AppointmentSummaryDto(
        val id: Long,
        val doctorId: Long,
        val doctorName: String,
        val doctorProfession: String?,
        val doctorClinic: String?,
        val doctorPhotoUrl: String?,
        val startAt: String,
        val endAt: String,
        val location: String,
        val reason: String?,
        val status: String,
        val paymentStatus: String,
        val paymentAmountMinor: Long?,
        val paymentCurrency: String?,
        val signatureRequested: Boolean,
        val alreadySigned: Boolean,
        val isVideoConsultation: Boolean = false,
        val paymentType: String? = null,
        val paymentBadge: String? = null,
        val serviceId: Long? = null,
        val serviceTitle: String? = null,
        val locationId: Long? = null,
        val locationLabel: String? = null,
        val locationAddress: String? = null,
        val documentsCount: Int = 0,
        val isLocked24h: Boolean = false,
        val canCancel: Boolean = false,
        val canReschedule: Boolean = false,
        val refundRequired: Boolean = false,
        val holdExpiresAt: String? = null,
    )

    @GetMapping("/{appointmentId}")
    @Transactional(readOnly = true)
    fun getById(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @PathVariable appointmentId: Long
    ): AppointmentSummaryDto {
        val patient = currentPatientProfile(principal)
        val appointment = appts.findByIdWithDoctorAndPatient(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }
        if (appointment.patient?.id != patient.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Appointment does not belong to you")
        }
        val doctor = appointment.doctor
        val doctorName = "${doctor.firstName ?: ""} ${doctor.lastName ?: ""}".trim().ifEmpty { "Doctor" }
        val locked = BookingPaymentPolicy.isLocked24h(appointment.startAt, Instant.now())
        val canChange = BookingPaymentPolicy.canPatientChange(appointment.status, locked)
        return try {
            AppointmentSummaryDto(
                id = appointment.id,
                doctorId = doctor.id,
                doctorName = doctorName,
                doctorProfession = doctor.profession,
                doctorClinic = appointment.locationRef?.clinic?.takeIf { it.isNotBlank() } ?: doctor.clinic,
                doctorPhotoUrl = profileMapper.normalizePhotoUrl(doctor.avatarUrl),
                startAt = appointment.startAt.toString(),
                endAt = appointment.endAt.toString(),
                location = appointment.location.ifEmpty { "—" },
                reason = appointment.reason,
                status = appointment.status.name,
                paymentStatus = appointment.paymentStatus.name,
                paymentAmountMinor = appointment.paymentAmountMinor,
                paymentCurrency = appointment.paymentCurrency,
                signatureRequested = appointment.signatureRequested,
                alreadySigned = appointment.patientSignatureImage != null,
                isVideoConsultation = appointment.isVideoConsultation,
                paymentType = appointment.paymentType?.name,
                paymentBadge = BookingPaymentPolicy.paymentBadge(appointment.paymentStatus),
                serviceId = appointment.serviceId,
                serviceTitle = appointment.serviceTitle,
                locationId = appointment.locationRef?.id,
                locationLabel = appointment.locationRef?.label,
                locationAddress = appointment.locationRef?.address,
                documentsCount = documents.findByAppointmentId(appointment.id).size,
                isLocked24h = locked,
                canCancel = canChange,
                canReschedule = canChange,
                refundRequired = appointment.refundRequired,
                holdExpiresAt = appointment.holdExpiresAt?.toString(),
            )
        } catch (e: Exception) {
            log.error("Failed to build appointment summary for id=$appointmentId", e)
            throw e
        }
    }

    @GetMapping("/{appointmentId}/review")
    @Transactional(readOnly = true)
    fun getMyReviewForAppointment(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @PathVariable appointmentId: Long
    ): AppointmentReviewDto {
        val patient = currentPatientProfile(principal)
        val appointment = appts.findByIdWithDoctorAndPatient(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }
        if (appointment.patient?.id != patient.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Appointment does not belong to you")
        }
        val review = reviewRepository.findByAppointmentId(appointmentId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No review for this appointment")
        if (review.patient.id != patient.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Review does not belong to you")
        }
        return AppointmentReviewDto(
            rating = review.rating,
            comment = review.comment,
            createdAt = review.createdAt.toString()
        )
    }

    data class SubmitSignatureRequest(
        val signatureImageBase64: String
    )

    @PostMapping("/{appointmentId}/submit-signature")
    @Transactional
    fun submitSignature(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @PathVariable appointmentId: Long,
        @RequestBody req: SubmitSignatureRequest
    ) {
        val patient = currentPatientProfile(principal)
        val appointment = appts.findByIdWithDoctorAndPatient(appointmentId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId")
            }
        if (appointment.patient?.id != patient.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "You can only sign your own appointment")
        }
        if (appointment.patientSignatureImage != null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Signature already submitted")
        }
        val base64 = req.signatureImageBase64.trim()
        if (base64.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Signature image is required")
        }
        appointment.patientSignatureImage = base64
        appointment.patientSignedAt = Instant.now()
        appts.save(appointment)
    }

    @PostMapping("/{appointmentId}/ai-visit-summary:generate")
    fun generateVisitSummary(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @PathVariable appointmentId: Long,
        @RequestBody(required = false) body: GenerateVisitSummaryRequest?
    ): Map<String, Any> {
        val patient = currentPatientProfile(principal)
        val appointment = appts.findByIdWithDoctorAndPatient(appointmentId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId") }
        if (appointment.patient?.id != patient.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Appointment does not belong to you")
        }
        visitSummaryService.enqueueGeneration(
            appointmentId = appointmentId,
            language = body?.language,
            force = body?.force == true
        )
        return mapOf("status" to "queued")
    }

    @GetMapping("/{appointmentId}/ai-visit-summary")
    fun getVisitSummary(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @PathVariable appointmentId: Long,
        @RequestParam(required = false) language: String?
    ): Map<String, Any?> {
        val patient = currentPatientProfile(principal)
        val appointment = appts.findByIdWithDoctorAndPatient(appointmentId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId") }
        if (appointment.patient?.id != patient.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Appointment does not belong to you")
        }
        val result = visitSummaryService.getForAppointment(appointmentId, language)
        return mapOf(
            "status" to result.status,
            "language" to result.language,
            "version" to result.version,
            "generatedAt" to result.generatedAt,
            "content" to result.content
        )
    }

    @PostMapping("/{appointmentId}/ai-visit-summary/ask")
    fun askVisitSummary(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @PathVariable appointmentId: Long,
        @RequestBody body: AskVisitSummaryRequest
    ): Map<String, Any> {
        val patient = currentPatientProfile(principal)
        val appointment = appts.findByIdWithDoctorAndPatient(appointmentId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId") }
        if (appointment.patient?.id != patient.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Appointment does not belong to you")
        }
        val answer = visitSummaryService.askForAppointment(
            appointmentId = appointmentId,
            language = body.language,
            question = body.question
        )
        return mapOf(
            "answer" to answer.answer,
            "citations" to answer.citations
        )
    }

    private fun currentPatientProfile(principal: PatientPrincipal): com.shifa.patients.domain.PatientProfile = currentPatient.resolve(principal)
}
