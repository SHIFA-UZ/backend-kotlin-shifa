package com.shifa.clinics.application

import com.shifa.clinics.domain.TreatmentPlanCatalogItem
import com.shifa.clinics.infrastructure.TreatmentPlanCatalogItemRepository
import java.util.Optional
import org.springframework.stereotype.Service

/**
 * The feature's door to TreatmentPlanCatalogItem records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [TreatmentPlanCatalogItemRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class TreatmentPlanCatalogItems(private val repo: TreatmentPlanCatalogItemRepository) {
    fun findByClinic_IdOrderByActiveDescSortOrderAscIdAsc(clinicId: Long): List<TreatmentPlanCatalogItem> = repo.findByClinic_IdOrderByActiveDescSortOrderAscIdAsc(clinicId)
    fun findById(id: Long): Optional<TreatmentPlanCatalogItem> = repo.findById(id)
    fun save(entity: TreatmentPlanCatalogItem): TreatmentPlanCatalogItem = repo.save(entity)
}
