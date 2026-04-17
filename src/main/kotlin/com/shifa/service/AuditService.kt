package com.shifa.service

import com.shifa.domain.AuditLog
import com.shifa.domain.User
import com.shifa.repo.AuditLogRepository
import jakarta.servlet.http.HttpServletRequest
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class AuditService(
    private val auditLogRepository: AuditLogRepository
) {
    
    /**
     * Uses a new read-write transaction so callers inside @Transactional(readOnly = true)
     * (e.g. admin export) can still persist audit rows.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun logAction(
        adminUser: User,
        actionType: String,
        entityType: String,
        entityId: Long? = null,
        details: Map<String, Any>? = null,
        request: HttpServletRequest? = null
    ) {
        val log = AuditLog(
            adminUser = adminUser,
            actionType = actionType,
            entityType = entityType,
            entityId = entityId,
            details = details,
            ipAddress = request?.remoteAddr,
            userAgent = request?.getHeader("User-Agent")
        )
        auditLogRepository.save(log)
    }
    
    fun getAdminActivity(adminUserId: Long, pageable: Pageable): Page<AuditLog> {
        return auditLogRepository.findByAdminUserIdOrderByCreatedAtDesc(adminUserId, pageable)
    }
    
    fun getEntityActivity(entityType: String, entityId: Long, pageable: Pageable): Page<AuditLog> {
        return auditLogRepository.findByEntity(entityType, entityId, pageable)
    }
    
    fun getActivityByType(actionType: String, since: OffsetDateTime, pageable: Pageable): Page<AuditLog> {
        return auditLogRepository.findByActionTypeSince(actionType, since, pageable)
    }
    
    fun getActivityBetween(start: OffsetDateTime, end: OffsetDateTime, pageable: Pageable): Page<AuditLog> {
        return auditLogRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, pageable)
    }
}
