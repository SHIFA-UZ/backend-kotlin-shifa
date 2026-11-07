package com.shifa.clinics.application

import com.shifa.clinics.domain.TreatmentPlan
import com.shifa.clinics.domain.TreatmentPlanPayment
import com.shifa.clinics.infrastructure.TreatmentPlanPaymentRepository
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service

/**
 * The feature's door to TreatmentPlanPayment records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [TreatmentPlanPaymentRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class TreatmentPlanPayments(private val repo: TreatmentPlanPaymentRepository) {
    fun findByPlan_IdOrderByRecordedAtAsc(planId: Long): List<TreatmentPlanPayment> = repo.findByPlan_IdOrderByRecordedAtAsc(planId)
    fun findByPlanIdsOrderByRecordedAtAsc(planIds: Collection<Long>): List<TreatmentPlanPayment> = repo.findByPlanIdsOrderByRecordedAtAsc(planIds)
    fun findByClinicAndPlanStatuses(clinicId: Long, statuses: List<TreatmentPlan.Status>, pageable: Pageable): List<TreatmentPlanPayment> = repo.findByClinicAndPlanStatuses(clinicId, statuses, pageable)
    fun findByClinicAndPlanStatusesAndPatientIds(clinicId: Long, statuses: List<TreatmentPlan.Status>, patientIds: Collection<Long>, pageable: Pageable): List<TreatmentPlanPayment> = repo.findByClinicAndPlanStatusesAndPatientIds(clinicId, statuses, patientIds, pageable)
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun findByClinicAndPlanStatusesInRange(clinicId: Long, statuses: List<TreatmentPlan.Status>, from: java.time.OffsetDateTime, to: java.time.OffsetDateTime, pageable: Pageable): List<TreatmentPlanPayment> = repo.findByClinicAndPlanStatusesInRange(clinicId, statuses, from, to, pageable)
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun findByClinicAndPlanStatusesAndPatientIdsInRange(clinicId: Long, statuses: List<TreatmentPlan.Status>, patientIds: Collection<Long>, from: java.time.OffsetDateTime, to: java.time.OffsetDateTime, pageable: Pageable): List<TreatmentPlanPayment> = repo.findByClinicAndPlanStatusesAndPatientIdsInRange(clinicId, statuses, patientIds, from, to, pageable)
    fun save(entity: TreatmentPlanPayment): TreatmentPlanPayment = repo.save(entity)
}
