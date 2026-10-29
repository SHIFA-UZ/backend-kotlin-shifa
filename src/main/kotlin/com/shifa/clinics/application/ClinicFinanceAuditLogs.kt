package com.shifa.clinics.application

import com.shifa.clinics.domain.ClinicFinanceAuditLog
import com.shifa.clinics.infrastructure.ClinicFinanceAuditLogRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service

/**
 * The feature's door to ClinicFinanceAuditLog records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [ClinicFinanceAuditLogRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class ClinicFinanceAuditLogs(private val repo: ClinicFinanceAuditLogRepository) {
    fun findByClinic_IdOrderByCreatedAtDesc(clinicId: Long, pageable: Pageable): Page<ClinicFinanceAuditLog> = repo.findByClinic_IdOrderByCreatedAtDesc(clinicId, pageable)
    fun findByClinic_IdAndEntityTypeAndEntityIdOrderByCreatedAtDesc(clinicId: Long, entityType: String, entityId: Long, pageable: Pageable): Page<ClinicFinanceAuditLog> = repo.findByClinic_IdAndEntityTypeAndEntityIdOrderByCreatedAtDesc(clinicId, entityType, entityId, pageable)
}
