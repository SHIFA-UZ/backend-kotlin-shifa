package com.shifa.repo

import com.shifa.domain.DoctorService
import org.springframework.data.jpa.repository.JpaRepository

interface DoctorServiceRepository : JpaRepository<DoctorService, Long> {
    fun findByDoctorIdOrderByCreatedAtAsc(doctorId: Long): List<DoctorService>
    fun findByDoctorIdAndIsActiveTrueOrderByCreatedAtAsc(doctorId: Long): List<DoctorService>
}
