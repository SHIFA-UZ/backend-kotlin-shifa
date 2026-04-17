package com.shifa.repo

import com.shifa.domain.AuditLog
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

@Repository
interface AuditLogRepository : JpaRepository<AuditLog, Long> {
    fun findByAdminUserIdOrderByCreatedAtDesc(adminUserId: Long, pageable: Pageable): Page<AuditLog>
    
    @Query("SELECT a FROM AuditLog a WHERE a.entityType = :entityType AND a.entityId = :entityId ORDER BY a.createdAt DESC")
    fun findByEntity(@Param("entityType") entityType: String, @Param("entityId") entityId: Long, pageable: Pageable): Page<AuditLog>
    
    @Query("SELECT a FROM AuditLog a WHERE a.actionType = :actionType AND a.createdAt >= :since ORDER BY a.createdAt DESC")
    fun findByActionTypeSince(
        @Param("actionType") actionType: String,
        @Param("since") since: OffsetDateTime,
        pageable: Pageable
    ): Page<AuditLog>
    
    fun findByCreatedAtBetweenOrderByCreatedAtDesc(
        start: OffsetDateTime,
        end: OffsetDateTime,
        pageable: Pageable
    ): Page<AuditLog>
}
