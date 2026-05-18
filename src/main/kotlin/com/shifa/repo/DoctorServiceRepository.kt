package com.shifa.repo

import com.shifa.domain.DoctorService
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface DoctorServiceRepository : JpaRepository<DoctorService, Long> {
    @EntityGraph(attributePaths = ["group"])
    fun findByDoctorIdOrderByCreatedAtAsc(doctorId: Long): List<DoctorService>

    @EntityGraph(attributePaths = ["group"])
    fun findByDoctorIdAndIsActiveTrueOrderByCreatedAtAsc(doctorId: Long): List<DoctorService>

    fun findAllBySourceCatalogItem_Id(catalogItemId: Long): List<DoctorService>

    fun findByDoctor_IdAndSourceCatalogItem_Id(
        doctorId: Long,
        catalogItemId: Long,
    ): DoctorService?
}
