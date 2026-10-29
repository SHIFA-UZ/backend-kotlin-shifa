package com.shifa.doctors.application

import com.shifa.doctors.domain.DoctorService
import com.shifa.doctors.infrastructure.DoctorServiceRepository
import java.util.Optional
import org.springframework.stereotype.Service

/**
 * The feature's door to DoctorService records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [DoctorServiceRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class DoctorServices(private val repo: DoctorServiceRepository) {
    fun findByDoctorIdOrderByCreatedAtAsc(doctorId: Long): List<DoctorService> = repo.findByDoctorIdOrderByCreatedAtAsc(doctorId)
    fun findByDoctorIdAndIsActiveTrueOrderByCreatedAtAsc(doctorId: Long): List<DoctorService> = repo.findByDoctorIdAndIsActiveTrueOrderByCreatedAtAsc(doctorId)
    fun findAllBySourceCatalogItem_Id(catalogItemId: Long): List<DoctorService> = repo.findAllBySourceCatalogItem_Id(catalogItemId)
    fun findByDoctor_IdAndSourceCatalogItem_Id(doctorId: Long, catalogItemId: Long): DoctorService? = repo.findByDoctor_IdAndSourceCatalogItem_Id(doctorId, catalogItemId)
    fun delete(entity: DoctorService) = repo.delete(entity)
    fun findById(id: Long): Optional<DoctorService> = repo.findById(id)
    fun save(entity: DoctorService): DoctorService = repo.save(entity)
}
