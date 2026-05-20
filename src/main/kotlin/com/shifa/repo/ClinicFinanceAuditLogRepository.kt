package com.shifa.repo

import com.shifa.domain.ClinicFinanceAuditLog
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface ClinicFinanceAuditLogRepository : JpaRepository<ClinicFinanceAuditLog, Long> {

    fun findByClinic_IdOrderByCreatedAtDesc(clinicId: Long, pageable: Pageable): Page<ClinicFinanceAuditLog>

    fun findByClinic_IdAndEntityTypeAndEntityIdOrderByCreatedAtDesc(
        clinicId: Long,
        entityType: String,
        entityId: Long,
        pageable: Pageable,
    ): Page<ClinicFinanceAuditLog>
}
