package com.shifa.web

import com.shifa.domain.SubscriptionFeature
import com.shifa.security.DoctorPrincipal
import com.shifa.service.DoctorPatientAccessService
import com.shifa.service.PatientBriefingService
import com.shifa.service.SubscriptionTierService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/ai")
class PatientBriefingController(
    private val briefingService: PatientBriefingService,
    private val doctorPatientAccess: DoctorPatientAccessService,
    private val subscriptionTierService: SubscriptionTierService
) {

    /** Same access rule as document list: appointment, created-by, or active care partnership. */
    private fun ensurePatientAccess(doctorId: Long, patientId: Long) {
        if (!doctorPatientAccess.doctorMayAccessPatient(doctorId, patientId)) {
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
        subscriptionTierService.requireFeature(
            principal.profile.user,
            SubscriptionFeature.PATIENT_BRIEFING
        )
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
