package com.shifa.clinics.application

import com.shifa.clinics.domain.InstallmentPlan
import com.shifa.clinics.infrastructure.InstallmentPlanRepository
import java.util.Optional
import org.springframework.stereotype.Service

/**
 * The feature's door to InstallmentPlan records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [InstallmentPlanRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class InstallmentPlans(private val repo: InstallmentPlanRepository) {
    fun findByTreatmentPlan_IdOrderByCreatedAtDesc(treatmentPlanId: Long): List<InstallmentPlan> = repo.findByTreatmentPlan_IdOrderByCreatedAtDesc(treatmentPlanId)
    fun findById(id: Long): Optional<InstallmentPlan> = repo.findById(id)
}
