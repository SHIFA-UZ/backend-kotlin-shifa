// src/main/kotlin/com/shifa/web/PatientAppointmentController.kt
package com.shifa.web

import com.shifa.repo.AppointmentRepository
import com.shifa.repo.DoctorReviewRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.security.PatientPrincipal
import com.shifa.service.PatientVisitAiSummaryService
import com.shifa.service.PatientProfileMapper
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
    private val appts: AppointmentRepository,
    private val patientProfiles: PatientProfileRepository,
    private val profileMapper: PatientProfileMapper,
    private val reviewRepository: DoctorReviewRepository,
    private val visitSummaryService: PatientVisitAiSummaryService
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
        val signatureRequested: Boolean,
        val alreadySigned: Boolean
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
        return try {
            AppointmentSummaryDto(
                id = appointment.id,
                doctorId = doctor.id,
                doctorName = doctorName,
                doctorProfession = doctor.profession,
                doctorClinic = doctor.clinic,
                doctorPhotoUrl = profileMapper.normalizePhotoUrl(doctor.avatarUrl),
                startAt = appointment.startAt.toString(),
                endAt = appointment.endAt.toString(),
                location = appointment.location.ifEmpty { "—" },
                reason = appointment.reason,
                status = appointment.status.name,
                signatureRequested = appointment.signatureRequested,
                alreadySigned = appointment.patientSignatureImage != null
            )
        } catch (e: Exception) {
            log.error("Failed to build appointment summary for id=$appointmentId", e)
            throw e
        }
    }

    /** Response when the patient has already submitted a review for this appointment. */
    data class AppointmentReviewDto(
        val rating: Int,
        val comment: String?,
        val createdAt: String
    )

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

    data class GenerateVisitSummaryRequest(
        val language: String? = null,
        val force: Boolean? = false
    )

    data class AskVisitSummaryRequest(
        val language: String? = null,
        val question: String
    )

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

    private fun currentPatientProfile(principal: PatientPrincipal): com.shifa.domain.PatientProfile {
        val user = principal.user
        val byPhone = user.phone?.let { patientProfiles.findByPhone(it).orElse(null) }
        if (byPhone != null) return byPhone
        val byEmail = user.email?.let { patientProfiles.findByEmail(it).orElse(null) }
        if (byEmail != null) return byEmail
        throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient profile not found for user ${user.id}")
    }
}
