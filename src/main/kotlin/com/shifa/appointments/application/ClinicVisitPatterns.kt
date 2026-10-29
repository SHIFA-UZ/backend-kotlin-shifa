package com.shifa.appointments.application

import com.shifa.appointments.domain.ClinicVisitPattern
import com.shifa.appointments.infrastructure.ClinicVisitPatternRepository
import org.springframework.stereotype.Service

/**
 * The feature's door to ClinicVisitPattern records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [ClinicVisitPatternRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class ClinicVisitPatterns(private val repo: ClinicVisitPatternRepository) {
    fun findByDoctorIdOrderByOccurrenceCountDesc(doctorId: Long): List<ClinicVisitPattern> = repo.findByDoctorIdOrderByOccurrenceCountDesc(doctorId)
}
