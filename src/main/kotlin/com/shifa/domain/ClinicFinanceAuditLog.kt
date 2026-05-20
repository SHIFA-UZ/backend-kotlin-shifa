package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "clinic_finance_audit_log")
class ClinicFinanceAuditLog(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clinic_id", nullable = false)
    val clinic: Clinic,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(name = "action_type", nullable = false, length = 64)
    val actionType: String,

    @Column(name = "entity_type", nullable = false, length = 64)
    val entityType: String,

    @Column(name = "entity_id")
    val entityId: Long? = null,

    @Column(columnDefinition = "JSONB")
    val details: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()
)
