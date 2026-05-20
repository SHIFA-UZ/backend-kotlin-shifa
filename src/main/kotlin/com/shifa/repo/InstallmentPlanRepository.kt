package com.shifa.repo

import com.shifa.domain.InstallmentPlan
import org.springframework.data.jpa.repository.JpaRepository

interface InstallmentPlanRepository : JpaRepository<InstallmentPlan, Long> {

    fun findByTreatmentPlan_IdOrderByCreatedAtDesc(treatmentPlanId: Long): List<InstallmentPlan>

    fun findByTreatmentPlan_IdAndStatus(treatmentPlanId: Long, status: InstallmentPlan.Status): List<InstallmentPlan>
}
