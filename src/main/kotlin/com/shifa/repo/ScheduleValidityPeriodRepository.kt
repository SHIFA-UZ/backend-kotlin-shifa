package com.shifa.repo

import com.shifa.domain.ScheduleValidityPeriod
import org.springframework.data.jpa.repository.JpaRepository

interface ScheduleValidityPeriodRepository : JpaRepository<ScheduleValidityPeriod, Long> {

    fun findByDoctorIdOrderByValidFromAsc(doctorId: Long): List<ScheduleValidityPeriod>
}
