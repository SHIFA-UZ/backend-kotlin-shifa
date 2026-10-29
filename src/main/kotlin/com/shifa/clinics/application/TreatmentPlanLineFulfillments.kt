package com.shifa.clinics.application

import com.shifa.clinics.infrastructure.TreatmentPlanLineFulfillmentRepository
import org.springframework.stereotype.Service

/**
 * The feature's door to TreatmentPlanLineFulfillment records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [TreatmentPlanLineFulfillmentRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class TreatmentPlanLineFulfillments(private val repo: TreatmentPlanLineFulfillmentRepository) {
    fun findLineIdsByAppointmentId(appointmentId: Long): List<Long> = repo.findLineIdsByAppointmentId(appointmentId)
}
