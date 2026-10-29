package com.shifa.clinics.application

import com.shifa.clinics.domain.Clinic
import com.shifa.clinics.infrastructure.ClinicRepository
import java.util.Optional
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service

/**
 * The feature's door to Clinic records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [ClinicRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class Clinics(private val repo: ClinicRepository) {
    fun existsById(id: Long): Boolean = repo.existsById(id)
    fun findAll(): List<Clinic> = repo.findAll()
    fun findAll(pageable: Pageable): Page<Clinic> = repo.findAll(pageable)
    fun findAll(sort: Sort): List<Clinic> = repo.findAll(sort)
    fun findById(id: Long): Optional<Clinic> = repo.findById(id)
    fun save(entity: Clinic): Clinic = repo.save(entity)
}
