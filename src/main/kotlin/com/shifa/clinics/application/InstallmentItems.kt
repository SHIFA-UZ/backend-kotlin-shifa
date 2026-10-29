package com.shifa.clinics.application

import com.shifa.clinics.domain.InstallmentItem
import com.shifa.clinics.infrastructure.InstallmentItemRepository
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Optional
import org.springframework.stereotype.Service

/**
 * The feature's door to InstallmentItem records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [InstallmentItemRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class InstallmentItems(private val repo: InstallmentItemRepository) {
    fun findByInstallmentPlan_IdOrderBySequenceNumberAsc(installmentPlanId: Long): List<InstallmentItem> = repo.findByInstallmentPlan_IdOrderBySequenceNumberAsc(installmentPlanId)
    fun findOverdueByClinic(clinicId: Long, today: LocalDate): List<InstallmentItem> = repo.findOverdueByClinic(clinicId, today)
    fun findActiveByClinic(clinicId: Long): List<InstallmentItem> = repo.findActiveByClinic(clinicId)
    fun findAllPendingDueOn(dueDate: LocalDate): List<InstallmentItem> = repo.findAllPendingDueOn(dueDate)
    fun findUnpaidByTreatmentPlan(treatmentPlanId: Long, statuses: List<InstallmentItem.Status>): List<InstallmentItem> = repo.findUnpaidByTreatmentPlan(treatmentPlanId, statuses)
    fun findOverdueNeedingRemind(before: OffsetDateTime): List<InstallmentItem> = repo.findOverdueNeedingRemind(before)
    fun findById(id: Long): Optional<InstallmentItem> = repo.findById(id)
    fun save(entity: InstallmentItem): InstallmentItem = repo.save(entity)
}
