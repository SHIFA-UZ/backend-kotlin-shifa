package com.shifa.appointments.infrastructure

import com.shifa.appointments.domain.ScheduleValidityPeriod
import org.springframework.data.jpa.repository.JpaRepository

interface ScheduleValidityPeriodRepository : JpaRepository<ScheduleValidityPeriod, Long> {

    fun findByDoctorIdOrderByValidFromAsc(doctorId: Long): List<ScheduleValidityPeriod>
}
