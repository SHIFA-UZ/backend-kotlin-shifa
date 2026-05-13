package com.shifa.repo

import com.shifa.domain.DoctorServiceGroup
import org.springframework.data.jpa.repository.JpaRepository

interface DoctorServiceGroupRepository : JpaRepository<DoctorServiceGroup, Long> {
    fun findByDoctorIdOrderBySortOrderAscIdAsc(doctorId: Long): List<DoctorServiceGroup>
}
