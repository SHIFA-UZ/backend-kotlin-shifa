package com.shifa.repo

import com.shifa.domain.DoctorService
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface DoctorServiceRepository : JpaRepository<DoctorService, Long> {
    @EntityGraph(attributePaths = ["group"])
    fun findByDoctorIdOrderByCreatedAtAsc(doctorId: Long): List<DoctorService>

    @EntityGraph(attributePaths = ["group"])
    fun findByDoctorIdAndIsActiveTrueOrderByCreatedAtAsc(doctorId: Long): List<DoctorService>
}
