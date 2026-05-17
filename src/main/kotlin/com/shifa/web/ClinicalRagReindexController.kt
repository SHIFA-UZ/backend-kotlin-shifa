package com.shifa.web

import com.shifa.domain.SubscriptionFeature
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.security.DoctorPrincipal
import com.shifa.service.ClinicalRagIndexingService
import com.shifa.service.SubscriptionTierService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Operational endpoint to rebuild pgvector clinical chunks for a patient after enabling RAG or fixing bad indexes.
 */
@RestController
@RequestMapping("/api/ai")
class ClinicalRagReindexController(
    private val indexer: ClinicalRagIndexingService,
    private val subscriptionTierService: SubscriptionTierService,
    private val appointmentRepo: AppointmentRepository,
    private val patientsRepo: PatientProfileRepository,
) {

    @PostMapping("/clinical-rag/reindex-patient/{patientId}")
    fun reindexPatient(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable patientId: Long,
    ): ResponseEntity<Map<String, Any>> {
        subscriptionTierService.requireFeature(principal.profile.user, SubscriptionFeature.ASK_SHIFA_AI)
        ensurePatientAccess(principal.profile.id, patientId)
        val summary = indexer.reindexAllForPatient(patientId)
        return ResponseEntity.ok(
            mapOf(
                "success" to true,
                "forms0252Processed" to summary.patientForms,
                "consultationNotesProcessed" to summary.consultationNotes,
                "appointmentDentalProcessed" to summary.appointmentDental,
            )
        )
    }

    private fun ensurePatientAccess(doctorId: Long, patientId: Long) {
        if (appointmentRepo.existsByDoctorIdAndPatientId(doctorId, patientId)) return
        val patient = patientsRepo.findById(patientId).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
        if (patient.createdByDoctor?.id != doctorId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Patient not found")
        }
    }
}
