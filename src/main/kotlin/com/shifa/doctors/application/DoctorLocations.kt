package com.shifa.doctors.application

import com.shifa.doctors.domain.DoctorLocation
import com.shifa.doctors.infrastructure.DoctorLocationRepository
import java.util.Optional
import org.springframework.stereotype.Service

/**
 * The feature's door to DoctorLocation records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [DoctorLocationRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class DoctorLocations(private val repo: DoctorLocationRepository) {
    fun findByDoctorIdOrderByIsPrimaryDescIdAsc(doctorId: Long): List<DoctorLocation> = repo.findByDoctorIdOrderByIsPrimaryDescIdAsc(doctorId)
    fun findByDoctorIdAndIsPrimaryTrue(doctorId: Long): Optional<DoctorLocation> = repo.findByDoctorIdAndIsPrimaryTrue(doctorId)
    fun delete(entity: DoctorLocation) = repo.delete(entity)
    fun findById(id: Long): Optional<DoctorLocation> = repo.findById(id)
    fun save(entity: DoctorLocation): DoctorLocation = repo.save(entity)
}
