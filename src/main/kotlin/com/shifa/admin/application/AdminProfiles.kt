package com.shifa.admin.application

import com.shifa.admin.domain.AdminProfile
import com.shifa.admin.infrastructure.AdminProfileRepository
import java.util.Optional
import org.springframework.stereotype.Service

/**
 * The feature's door to AdminProfile records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [AdminProfileRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class AdminProfiles(private val repo: AdminProfileRepository) {
    fun findByUserId(userId: Long): Optional<AdminProfile> = repo.findByUserId(userId)
    fun findUserIdsBySearch(q: String): List<Long> = repo.findUserIdsBySearch(q)
    fun save(entity: AdminProfile): AdminProfile = repo.save(entity)
}
