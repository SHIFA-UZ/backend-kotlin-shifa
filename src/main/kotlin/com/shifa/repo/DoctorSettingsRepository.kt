package com.shifa.repo

import com.shifa.domain.DoctorSettings
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface DoctorSettingsRepository : JpaRepository<DoctorSettings, Long> {
    fun findByDoctorId(doctorId: Long): Optional<DoctorSettings>
}
