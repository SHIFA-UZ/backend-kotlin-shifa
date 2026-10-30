package com.shifa.doctors.infrastructure

import com.shifa.doctors.domain.DoctorService
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

    /** Active services of several doctors in one query (public listing), oldest first like the per-doctor variant. */
    @org.springframework.data.jpa.repository.Query(
        "SELECT s FROM DoctorService s LEFT JOIN FETCH s.group WHERE s.doctor.id IN :doctorIds AND s.isActive = true ORDER BY s.createdAt ASC, s.id ASC",
    )
    fun findActiveByDoctorIdsOrderByCreatedAtAsc(@org.springframework.data.repository.query.Param("doctorIds") doctorIds: Collection<Long>): List<DoctorService>
}
