package com.shifa.clinics.infrastructure

import com.shifa.clinics.domain.TreatmentPlanCatalogItemDoctor
import org.springframework.data.jpa.repository.JpaRepository

interface TreatmentPlanCatalogItemDoctorRepository : JpaRepository<TreatmentPlanCatalogItemDoctor, Long> {
    fun deleteByCatalogItem_Id(catalogItemId: Long): Long
    fun findByCatalogItem_Id(catalogItemId: Long): List<TreatmentPlanCatalogItemDoctor>
}
