package com.shifa.repo

import com.shifa.domain.TreatmentPlan
import com.shifa.domain.TreatmentPlanPayment
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface TreatmentPlanPaymentRepository : JpaRepository<TreatmentPlanPayment, Long> {
    fun findByPlan_IdOrderByRecordedAtAsc(planId: Long): List<TreatmentPlanPayment>

    @Query("""
        SELECT tpp FROM TreatmentPlanPayment tpp
        JOIN tpp.plan tp
        WHERE tp.clinic.id = :clinicId
          AND tp.status IN :statuses
        ORDER BY tpp.recordedAt DESC
    """)
    fun findByClinicAndPlanStatuses(
        clinicId: Long,
        statuses: List<TreatmentPlan.Status>,
        pageable: Pageable
    ): List<TreatmentPlanPayment>
}
