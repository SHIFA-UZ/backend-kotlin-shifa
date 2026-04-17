package com.shifa.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

@Entity
@Table(name = "audit_logs")
class AuditLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_user_id", nullable = false)
    val adminUser: User,

    @Column(name = "action_type", nullable = false)
    val actionType: String, // USER_CREATED, USER_DEACTIVATED, TOKEN_GENERATED, etc.

    @Column(name = "entity_type", nullable = false)
    val entityType: String, // USER, INVITATION_KEY, DOCTOR_PROFILE, etc.

    @Column(name = "entity_id")
    val entityId: Long? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    val details: Map<String, Any>? = null,

    @Column(name = "ip_address")
    val ipAddress: String? = null,

    @Column(name = "user_agent")
    val userAgent: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()
)
