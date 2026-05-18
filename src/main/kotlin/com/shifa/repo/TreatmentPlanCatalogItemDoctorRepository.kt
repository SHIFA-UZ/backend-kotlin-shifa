package com.shifa.repo

import com.shifa.domain.TreatmentPlanCatalogItemDoctor
import org.springframework.data.jpa.repository.JpaRepository

interface TreatmentPlanCatalogItemDoctorRepository : JpaRepository<TreatmentPlanCatalogItemDoctor, Long> {
    fun deleteByCatalogItem_Id(catalogItemId: Long): Long
    fun findByCatalogItem_Id(catalogItemId: Long): List<TreatmentPlanCatalogItemDoctor>
}
