package com.shifa.clinics.infrastructure

import com.shifa.clinics.domain.TreatmentPlan
import com.shifa.clinics.domain.TreatmentPlanPayment
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime

interface TreatmentPlanPaymentRepository : JpaRepository<TreatmentPlanPayment, Long> {
    fun findByPlan_IdOrderByRecordedAtAsc(planId: Long): List<TreatmentPlanPayment>

    fun findByOnlinePaymentId(onlinePaymentId: Long): TreatmentPlanPayment?

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

    /** Payments recorded in [from, to) (BE-11). */
    @Query(
        """
        SELECT tpp FROM TreatmentPlanPayment tpp
        JOIN tpp.plan tp
        WHERE tp.clinic.id = :clinicId
          AND tp.status IN :statuses
          AND tpp.recordedAt >= :from AND tpp.recordedAt < :to
        ORDER BY tpp.recordedAt DESC
        """
    )
    fun findByClinicAndPlanStatusesInRange(
        clinicId: Long,
        statuses: List<TreatmentPlan.Status>,
        from: OffsetDateTime,
        to: OffsetDateTime,
        pageable: Pageable,
    ): List<TreatmentPlanPayment>

    @Query(
        """
        SELECT tpp FROM TreatmentPlanPayment tpp
        JOIN tpp.plan tp
        WHERE tp.clinic.id = :clinicId
          AND tp.status IN :statuses
          AND tp.patient.id IN :patientIds
          AND tpp.recordedAt >= :from AND tpp.recordedAt < :to
        ORDER BY tpp.recordedAt DESC
        """
    )
    fun findByClinicAndPlanStatusesAndPatientIdsInRange(
        clinicId: Long,
        statuses: List<TreatmentPlan.Status>,
        patientIds: Collection<Long>,
        from: OffsetDateTime,
        to: OffsetDateTime,
        pageable: Pageable,
    ): List<TreatmentPlanPayment>
}
