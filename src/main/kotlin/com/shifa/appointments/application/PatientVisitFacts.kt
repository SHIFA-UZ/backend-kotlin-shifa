package com.shifa.appointments.application

import com.shifa.appointments.domain.PatientVisitFact
import com.shifa.appointments.infrastructure.PatientVisitFactRepository
import org.springframework.stereotype.Service

/**
 * The feature's door to PatientVisitFact records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [PatientVisitFactRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class PatientVisitFacts(private val repo: PatientVisitFactRepository) {
    fun findByAppointmentIdAndStatus(appointmentId: Long, status: PatientVisitFact.Status): List<PatientVisitFact> = repo.findByAppointmentIdAndStatus(appointmentId, status)
    fun findCommittedTimeline(doctorId: Long, patientId: Long): List<PatientVisitFact> = repo.findCommittedTimeline(doctorId, patientId)
}
