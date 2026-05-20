package com.shifa.service

import com.shifa.domain.Clinic
import com.shifa.domain.ClinicFinanceAuditLog
import com.shifa.domain.User
import com.shifa.repo.ClinicFinanceAuditLogRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@Service
class ClinicFinanceAuditService(
    private val auditRepo: ClinicFinanceAuditLogRepository,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(ClinicFinanceAuditService::class.java)

    /**
     * Dedicated REQUIRES_NEW transaction template for audit writes. The
     * `Exception` flow when persisting the audit log must never affect the
     * caller's transaction, so we drive the inner tx manually via this
     * template and catch *both* the body-time exception AND any commit-time
     * `UnexpectedRollbackException` Spring would otherwise raise.
     */
    private val auditTx: TransactionTemplate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    /**
     * Persist an audit log entry in its own short-lived transaction. The
     * audit log is best-effort — if the insert fails for any reason
     * (constraint, FK, Hibernate AssertionFailure, etc.) we log a warning
     * and let the caller's transaction proceed unaffected.
     *
     * Production bug history: previously the audit save ran on the caller's
     * persistence context and a null-id `AssertionFailure` on the
     * ClinicFinanceAuditLog entity would wedge the session, causing the
     * caller's next auto-flush (e.g. inside `mark-paid`) to abort with
     * either `AssertionFailure` or `UnexpectedRollbackException`.
     */
    fun log(
        clinic: Clinic,
        user: User,
        actionType: String,
        entityType: String,
        entityId: Long? = null,
        details: String? = null,
    ) {
        try {
            auditTx.executeWithoutResult {
                auditRepo.saveAndFlush(
                    ClinicFinanceAuditLog(
                        clinic = clinic,
                        user = user,
                        actionType = actionType,
                        entityType = entityType,
                        entityId = entityId,
                        details = details,
                    ),
                )
            }
        } catch (e: Exception) {
            // Covers both body-time exceptions (e.g. AssertionFailure during
            // saveAndFlush) AND commit-time exceptions
            // (UnexpectedRollbackException, TransactionSystemException, etc).
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
