package com.shifa.repo

import com.shifa.domain.DoctorLocation
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface DoctorLocationRepository : JpaRepository<DoctorLocation, Long> {

    fun findByDoctorIdOrderByIsPrimaryDescIdAsc(doctorId: Long): List<DoctorLocation>

    fun findByDoctorIdAndIsPrimaryTrue(doctorId: Long): Optional<DoctorLocation>

    fun countByDoctorId(doctorId: Long): Long
}
