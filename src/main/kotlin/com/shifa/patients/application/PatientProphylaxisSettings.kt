package com.shifa.patients.application

import com.shifa.patients.domain.PatientProphylaxisSetting
import com.shifa.patients.infrastructure.PatientProphylaxisSettingRepository
import org.springframework.stereotype.Service

/**
 * The feature's door to PatientProphylaxisSetting records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [PatientProphylaxisSettingRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class PatientProphylaxisSettings(private val repo: PatientProphylaxisSettingRepository) {
    fun findAllByEnabledTrue(): List<PatientProphylaxisSetting> = repo.findAllByEnabledTrue()
    fun findByPatient_IdAndClinic_Id(patientId: Long, clinicId: Long): PatientProphylaxisSetting? = repo.findByPatient_IdAndClinic_Id(patientId, clinicId)
    fun findEnabledPatientIdsIn(patientIds: Collection<Long>): List<Long> = repo.findEnabledPatientIdsIn(patientIds)
    fun save(entity: PatientProphylaxisSetting): PatientProphylaxisSetting = repo.save(entity)
}
