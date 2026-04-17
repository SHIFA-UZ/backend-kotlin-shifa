package com.shifa.ai

import com.shifa.repo.AppointmentRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.service.PatientDocumentService
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

@Component
class PatientAiContextBuilder(
    private val patientRepo: PatientProfileRepository,
    private val patientDocumentService: PatientDocumentService,
    private val appointmentRepo: AppointmentRepository
) {

    /**
     * Build a richer AI context for a given patient **and doctor**.
     *
     * - Includes patient age and preferred language
     * - Includes only documents this doctor can view (respecting access control)
     * - Includes recent non‑cancelled appointments this doctor had with the patient
     *
     * NOTE: This intentionally summarizes metadata (titles, dates, reasons) rather than full
     * document contents, to keep the streaming AI context compact. Full document content is
     * handled by the separate patient briefing flow.
     */
    fun build(patientId: Long, doctorId: Long): PatientAiContext {
        val patient = patientRepo.findById(patientId)
            .orElseThrow { IllegalArgumentException("Patient not found") }

        val age = patient.birthDate?.let {
            Period.between(it, LocalDate.now()).years
        }

        // Only include documents the doctor can actually view.
        val documentSummaries = patientDocumentService
            .listDocumentsWithAccess(patientId, doctorId)
            .take(5)
            .map { doc ->
                DocumentSummary(
                    title = doc.title,
                    date = doc.date
                )
            }

        // Recent appointments for this patient with this doctor (newest first).
        val appointmentSummaries = appointmentRepo
            .findByPatientIdAndDoctorIdOrderByStartAtDesc(patientId, doctorId)
            .take(10)
            .map { appt ->
                AppointmentSummary(
                    date = appt.startAt.atZone(ZoneId.systemDefault()).toLocalDate(),
                    reason = appt.reason
                )
            }

        return PatientAiContext(
            patientId = patient.id!!,
            age = age,
            language = patient.language, // nullable → OK
            documentSummaries = documentSummaries,
            appointmentSummaries = appointmentSummaries
        )
    }
}
