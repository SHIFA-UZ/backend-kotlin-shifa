package com.shifa.clinics.application

import com.shifa.clinics.domain.TreatmentPlanLine
import com.shifa.clinics.infrastructure.TreatmentPlanLineRepository
import java.util.Optional
import org.springframework.stereotype.Service

/**
 * The feature's door to TreatmentPlanLine records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [TreatmentPlanLineRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class TreatmentPlanLines(private val repo: TreatmentPlanLineRepository) {
    fun findByPlan_IdOrderBySortOrderAscIdAsc(planId: Long): List<TreatmentPlanLine> = repo.findByPlan_IdOrderBySortOrderAscIdAsc(planId)
    fun countDistinctLinkedAppointmentsByPlanId(planId: Long): Long = repo.countDistinctLinkedAppointmentsByPlanId(planId)
    fun countDistinctLinkedAppointmentsGrouped(planIds: Collection<Long>): List<Array<Any>> = repo.countDistinctLinkedAppointmentsGrouped(planIds)
    fun findLinkedAppointmentLinesForPlan(planId: Long): List<TreatmentPlanLine> = repo.findLinkedAppointmentLinesForPlan(planId)
    fun findByLinkedAppointment_Id(appointmentId: Long): List<TreatmentPlanLine> = repo.findByLinkedAppointment_Id(appointmentId)
    fun findDistinctLinkedAppointmentIdsForClinic(clinicId: Long, pageable: org.springframework.data.domain.Pageable): org.springframework.data.domain.Page<Long> = repo.findDistinctLinkedAppointmentIdsForClinic(clinicId, pageable)
    fun findDistinctLinkedAppointmentIdsForClinicAndPatients(clinicId: Long, patientIds: List<Long>, pageable: org.springframework.data.domain.Pageable): org.springframework.data.domain.Page<Long> = repo.findDistinctLinkedAppointmentIdsForClinicAndPatients(clinicId, patientIds, pageable)
    fun findDistinctLinkedAppointmentIdsForClinicInRange(clinicId: Long, from: java.time.Instant, toExclusive: java.time.Instant, pageable: org.springframework.data.domain.Pageable): org.springframework.data.domain.Page<Long> = repo.findDistinctLinkedAppointmentIdsForClinicInRange(clinicId, from, toExclusive, pageable)
    fun findDistinctLinkedAppointmentIdsForClinicAndPatientsInRange(clinicId: Long, patientIds: List<Long>, from: java.time.Instant, toExclusive: java.time.Instant, pageable: org.springframework.data.domain.Pageable): org.springframework.data.domain.Page<Long> = repo.findDistinctLinkedAppointmentIdsForClinicAndPatientsInRange(clinicId, patientIds, from, toExclusive, pageable)
    fun delete(entity: TreatmentPlanLine) = repo.delete(entity)
    fun findById(id: Long): Optional<TreatmentPlanLine> = repo.findById(id)
    fun save(entity: TreatmentPlanLine): TreatmentPlanLine = repo.save(entity)
}
