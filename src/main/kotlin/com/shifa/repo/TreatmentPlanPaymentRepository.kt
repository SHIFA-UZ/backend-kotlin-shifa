package com.shifa.repo

import com.shifa.domain.TreatmentPlanPayment
import org.springframework.data.jpa.repository.JpaRepository

interface TreatmentPlanPaymentRepository : JpaRepository<TreatmentPlanPayment, Long> {
    fun findByPlan_IdOrderByRecordedAtAsc(planId: Long): List<TreatmentPlanPayment>
}
