package com.shifa.doctors.application

import com.shifa.doctors.domain.Profession
import com.shifa.doctors.infrastructure.ProfessionRepository
import java.util.Optional
import org.springframework.stereotype.Service

/**
 * The feature's door to Profession records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [ProfessionRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class Professions(private val repo: ProfessionRepository) {
    fun findByIsActiveTrueOrderByCategoryAscDisplayOrderAsc(): List<Profession> = repo.findByIsActiveTrueOrderByCategoryAscDisplayOrderAsc()
    fun searchProfessions(query: String): List<Profession> = repo.searchProfessions(query)
    fun findById(id: Long): Optional<Profession> = repo.findById(id)
}
