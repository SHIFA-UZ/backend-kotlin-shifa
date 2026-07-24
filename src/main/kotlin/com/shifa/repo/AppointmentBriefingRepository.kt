package com.shifa.repo

import com.shifa.domain.AppointmentBriefing
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface AppointmentBriefingRepository : JpaRepository<AppointmentBriefing, Long> {
    fun findByAppointmentId(appointmentId: Long): Optional<AppointmentBriefing>

    fun findByAppointmentIdIn(appointmentIds: Collection<Long>): List<AppointmentBriefing>
}
