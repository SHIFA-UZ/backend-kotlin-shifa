package com.shifa.repo

import com.shifa.domain.PatientProphylaxisSetting
import org.springframework.data.jpa.repository.JpaRepository

interface PatientProphylaxisSettingRepository : JpaRepository<PatientProphylaxisSetting, Long> {
    fun findAllByEnabledTrue(): List<PatientProphylaxisSetting>

    fun findByPatient_IdAndClinic_Id(patientId: Long, clinicId: Long): PatientProphylaxisSetting?
}
