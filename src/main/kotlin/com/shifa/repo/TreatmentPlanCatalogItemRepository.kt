package com.shifa.repo

import com.shifa.domain.TreatmentPlanCatalogItem
import org.springframework.data.jpa.repository.JpaRepository

interface TreatmentPlanCatalogItemRepository : JpaRepository<TreatmentPlanCatalogItem, Long> {
    fun findByClinic_IdAndActiveTrueOrderBySortOrderAscIdAsc(clinicId: Long): List<TreatmentPlanCatalogItem>

    fun findByClinic_IdOrderByActiveDescSortOrderAscIdAsc(clinicId: Long): List<TreatmentPlanCatalogItem>
}
