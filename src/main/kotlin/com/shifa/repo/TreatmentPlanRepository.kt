package com.shifa.repo

import com.shifa.domain.TreatmentPlan
import org.springframework.data.jpa.repository.JpaRepository

interface TreatmentPlanRepository : JpaRepository<TreatmentPlan, Long> {
    fun findByClinic_IdAndPatient_IdOrderByCreatedAtDesc(clinicId: Long, patientId: Long): List<TreatmentPlan>

    fun findByClinic_IdAndStatus(clinicId: Long, status: TreatmentPlan.Status): List<TreatmentPlan>

    fun findByStatusIn(statuses: List<TreatmentPlan.Status>): List<TreatmentPlan>

    fun findByClinic_IdAndRemainingAmountMinorGreaterThan(clinicId: Long, amount: Long): List<TreatmentPlan>
}
