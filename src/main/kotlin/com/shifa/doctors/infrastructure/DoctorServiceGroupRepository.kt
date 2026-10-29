package com.shifa.doctors.infrastructure

import com.shifa.doctors.domain.DoctorServiceGroup
import org.springframework.data.jpa.repository.JpaRepository

interface DoctorServiceGroupRepository : JpaRepository<DoctorServiceGroup, Long> {
    fun findByDoctorIdOrderBySortOrderAscIdAsc(doctorId: Long): List<DoctorServiceGroup>
}
