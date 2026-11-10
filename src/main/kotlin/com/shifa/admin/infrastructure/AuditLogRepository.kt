package com.shifa.admin.infrastructure

import com.shifa.admin.domain.AuditLog
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface AuditLogRepository : JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
    @Query("SELECT DISTINCT a.actionType FROM AuditLog a ORDER BY a.actionType")
    fun findDistinctActionTypes(): List<String>

    @Query("SELECT DISTINCT a.entityType FROM AuditLog a ORDER BY a.entityType")
    fun findDistinctEntityTypes(): List<String>
}
