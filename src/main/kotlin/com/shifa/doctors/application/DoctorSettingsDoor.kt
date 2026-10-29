package com.shifa.doctors.application

import com.shifa.doctors.domain.DoctorSettings
import com.shifa.doctors.infrastructure.DoctorSettingsRepository
import java.util.Optional
import org.springframework.stereotype.Service

/**
 * The feature's door to DoctorSettings records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [DoctorSettingsRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class DoctorSettingsDoor(private val repo: DoctorSettingsRepository) {
    fun findByDoctorId(doctorId: Long): Optional<DoctorSettings> = repo.findByDoctorId(doctorId)
    fun delete(entity: DoctorSettings) = repo.delete(entity)
    fun save(entity: DoctorSettings): DoctorSettings = repo.save(entity)
}
