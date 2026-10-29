package com.shifa.doctors.application

import com.shifa.doctors.domain.DoctorServiceGroup
import com.shifa.doctors.infrastructure.DoctorServiceGroupRepository
import java.util.Optional
import org.springframework.stereotype.Service

/**
 * The feature's door to DoctorServiceGroup records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [DoctorServiceGroupRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class DoctorServiceGroups(private val repo: DoctorServiceGroupRepository) {
    fun findByDoctorIdOrderBySortOrderAscIdAsc(doctorId: Long): List<DoctorServiceGroup> = repo.findByDoctorIdOrderBySortOrderAscIdAsc(doctorId)
    fun delete(entity: DoctorServiceGroup) = repo.delete(entity)
    fun findById(id: Long): Optional<DoctorServiceGroup> = repo.findById(id)
    fun save(entity: DoctorServiceGroup): DoctorServiceGroup = repo.save(entity)
}
