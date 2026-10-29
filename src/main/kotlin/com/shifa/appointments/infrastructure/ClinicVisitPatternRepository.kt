package com.shifa.appointments.infrastructure

import com.shifa.appointments.domain.ClinicVisitPattern
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface ClinicVisitPatternRepository : JpaRepository<ClinicVisitPattern, Long> {
    fun findByDoctorIdAndPatternKey(doctorId: Long, patternKey: String): Optional<ClinicVisitPattern>

    fun findByDoctorIdOrderByOccurrenceCountDesc(doctorId: Long): List<ClinicVisitPattern>
}