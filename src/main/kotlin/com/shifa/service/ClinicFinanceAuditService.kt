package com.shifa.service

import com.shifa.domain.Clinic
import com.shifa.domain.ClinicFinanceAuditLog
import com.shifa.domain.User
import com.shifa.repo.ClinicFinanceAuditLogRepository
import org.springframework.stereotype.Service

@Service
class ClinicFinanceAuditService(
    private val auditRepo: ClinicFinanceAuditLogRepository,
) {

    fun log(
        clinic: Clinic,
        user: User,
        actionType: String,
        entityType: String,
        entityId: Long? = null,
        details: String? = null
    ) {
        auditRepo.save(
            ClinicFinanceAuditLog(
                clinic = clinic,
                user = user,
                actionType = actionType,
                entityType = entityType,
                entityId = entityId,
                details = details
            )
        )
    }
}
