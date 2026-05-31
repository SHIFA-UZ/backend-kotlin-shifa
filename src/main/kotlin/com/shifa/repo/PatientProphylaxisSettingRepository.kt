package com.shifa.repo

import com.shifa.domain.PatientProphylaxisSetting
import org.springframework.data.jpa.repository.JpaRepository

interface PatientProphylaxisSettingRepository : JpaRepository<PatientProphylaxisSetting, Long> {
    fun findAllByEnabledTrue(): List<PatientProphylaxisSetting>

    fun findByPatient_IdAndClinic_Id(patientId: Long, clinicId: Long): PatientProphylaxisSetting?

    @org.springframework.data.jpa.repository.Query(
        """
        SELECT s.patient.id FROM PatientProphylaxisSetting s
        WHERE s.enabled = true AND s.patient.id IN :patientIds
        """
    )
    fun findEnabledPatientIdsIn(
        @org.springframework.data.repository.query.Param("patientIds") patientIds: Collection<Long>,
    ): List<Long>
}
