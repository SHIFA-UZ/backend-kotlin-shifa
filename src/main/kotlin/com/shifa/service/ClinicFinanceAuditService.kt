package com.shifa.service

import com.shifa.domain.Clinic
import com.shifa.domain.ClinicFinanceAuditLog
import com.shifa.domain.User
import com.shifa.repo.ClinicFinanceAuditLogRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class ClinicFinanceAuditService(
    private val auditRepo: ClinicFinanceAuditLogRepository,
) {
    private val log = LoggerFactory.getLogger(ClinicFinanceAuditService::class.java)

    /**
     * Persist an audit log entry in its OWN transaction so that an audit
     * failure can never wedge the caller's persistence context (a previous
     * production bug caused Hibernate `AssertionFailure: null id in
     * ClinicFinanceAuditLog entry` during the caller's auto-flush). The audit
     * log is best-effort; if it cannot be written we log a warning and let the
     * caller's transaction proceed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun log(
        clinic: Clinic,
        user: User,
        actionType: String,
        entityType: String,
        entityId: Long? = null,
        details: String? = null
    ) {
        try {
            auditRepo.saveAndFlush(
                ClinicFinanceAuditLog(
                    clinic = clinic,
                    user = user,
                    actionType = actionType,
                    entityType = entityType,
                    entityId = entityId,
                    details = details
                )
            )
        } catch (e: Exception) {
            log.warn(
                "Audit log save failed (clinicId={} userId={} action={} entityType={} entityId={}): {} {}",
                clinic.id,
                user.id,
                actionType,
                entityType,
                entityId,
                e.javaClass.simpleName,
                e.message,
            )
        }
    }
}
