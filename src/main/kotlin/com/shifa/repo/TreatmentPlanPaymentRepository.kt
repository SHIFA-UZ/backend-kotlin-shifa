package com.shifa.repo

import com.shifa.domain.TreatmentPlan
import com.shifa.domain.TreatmentPlanPayment
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface TreatmentPlanPaymentRepository : JpaRepository<TreatmentPlanPayment, Long> {
    fun findByPlan_IdOrderByRecordedAtAsc(planId: Long): List<TreatmentPlanPayment>

    @Query(
        """
        SELECT tpp FROM TreatmentPlanPayment tpp
        WHERE tpp.plan.id IN :planIds
        ORDER BY tpp.plan.id ASC, tpp.recordedAt ASC
        """,
    )
    fun findByPlanIdsOrderByRecordedAtAsc(planIds: Collection<Long>): List<TreatmentPlanPayment>

    @Query(
        """
        SELECT tpp FROM TreatmentPlanPayment tpp
        JOIN tpp.plan tp
        WHERE tp.clinic.id = :clinicId
          AND tp.status IN :statuses
        ORDER BY tpp.recordedAt DESC
        """
    )
    fun findByClinicAndPlanStatuses(
        clinicId: Long,
        statuses: List<TreatmentPlan.Status>,
        pageable: Pageable,
    ): List<TreatmentPlanPayment>

    @Query(
        """
        SELECT tpp FROM TreatmentPlanPayment tpp
        JOIN tpp.plan tp
        WHERE tp.clinic.id = :clinicId
          AND tp.status IN :statuses
          AND tp.patient.id IN :patientIds
        ORDER BY tpp.recordedAt DESC
        """
    )
    fun findByClinicAndPlanStatusesAndPatientIds(
        clinicId: Long,
        statuses: List<TreatmentPlan.Status>,
        patientIds: Collection<Long>,
        pageable: Pageable,
    ): List<TreatmentPlanPayment>
}
