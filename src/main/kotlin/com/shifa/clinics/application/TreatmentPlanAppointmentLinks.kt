package com.shifa.clinics.application

import com.shifa.clinics.infrastructure.TreatmentPlanAppointmentLinkRepository
import org.springframework.stereotype.Service

/**
 * The feature's door to TreatmentPlanAppointmentLink records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [TreatmentPlanAppointmentLinkRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class TreatmentPlanAppointmentLinks(private val repo: TreatmentPlanAppointmentLinkRepository) {
    fun findPlanIdAndTitleByAppointmentId(appointmentId: Long): List<Array<Any>> = repo.findPlanIdAndTitleByAppointmentId(appointmentId)
}
