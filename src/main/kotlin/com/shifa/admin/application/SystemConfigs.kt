package com.shifa.admin.application

import com.shifa.admin.domain.SystemConfig
import com.shifa.admin.infrastructure.SystemConfigRepository
import java.util.Optional
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service

/**
 * The feature's door to SystemConfig records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [SystemConfigRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class SystemConfigs(private val repo: SystemConfigRepository) {
    fun findByKey(key: String): Optional<SystemConfig> = repo.findByKey(key)
    fun findByKeyForUpdate(key: String): Optional<SystemConfig> = repo.findByKeyForUpdate(key)
    fun findAll(): List<SystemConfig> = repo.findAll()
    fun findAll(pageable: Pageable): Page<SystemConfig> = repo.findAll(pageable)
    fun findAll(sort: Sort): List<SystemConfig> = repo.findAll(sort)
    fun save(entity: SystemConfig): SystemConfig = repo.save(entity)
}
