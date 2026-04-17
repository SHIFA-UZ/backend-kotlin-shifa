package com.shifa.web

import com.shifa.repo.AppointmentRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.security.DoctorPrincipal
import com.shifa.service.PatientBriefingService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus

@RestController
@RequestMapping("/api/ai")
class PatientBriefingController(
    private val briefingService: PatientBriefingService,
    private val appointmentRepo: AppointmentRepository,
    private val patientsRepo: PatientProfileRepository
) {

    /** Same access rule as document list: appointment with doctor or patient created by doctor. */
    private fun ensurePatientAccess(doctorId: Long, patientId: Long) {
        if (appointmentRepo.existsByDoctorIdAndPatientId(doctorId, patientId)) return
        val patient = patientsRepo.findById(patientId).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
        if (patient.createdByDoctor?.id != doctorId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Patient not found")
        }
    }

    /**
     * POST /api/ai/patient-briefing/{patientId}
     * Generate a short AI briefing from the patient's documents (only those the doctor can view).
     * Body optional: { "language": "en" | "uz" | "ru" } for output language. Default "en".
     */
    @PostMapping("/patient-briefing/{patientId}")
    fun generateBriefing(
        @PathVariable patientId: Long,
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody(required = false) body: Map<String, Any>?
    ): ResponseEntity<Map<String, Any>> {
        ensurePatientAccess(principal.profile.id, patientId)
        val language = (body?.get("language") as? String)?.takeIf { it.isNotBlank() } ?: "en"
        return try {
            val result = briefingService.generateBriefing(patientId, principal.profile.id, language)
            ResponseEntity.ok(mapOf(
                "briefing" to result.briefing,
                "documentCount" to result.documentCount,
                "appointmentCount" to result.appointmentCount
            ))
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }
    }
}
