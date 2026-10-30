package com.shifa.doctors.infrastructure

import com.shifa.doctors.domain.DoctorLocation
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface DoctorLocationRepository : JpaRepository<DoctorLocation, Long> {

    fun findByDoctorIdOrderByIsPrimaryDescIdAsc(doctorId: Long): List<DoctorLocation>

    fun findByDoctorIdAndIsPrimaryTrue(doctorId: Long): Optional<DoctorLocation>

    fun countByDoctorId(doctorId: Long): Long

    /** Locations of several doctors in one query (public listing), primary first like the per-doctor variant. */
    @Suppress("FunctionNaming")
    fun findByDoctor_IdInOrderByIsPrimaryDescIdAsc(doctorIds: Collection<Long>): List<DoctorLocation>
}
