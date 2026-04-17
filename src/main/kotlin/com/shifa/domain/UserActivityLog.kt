package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "user_activity_logs")
class UserActivityLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(name = "activity_type", nullable = false)
    val activityType: String, // LOGIN, LOGOUT, PASSWORD_RESET, etc.

    @Column(name = "ip_address")
    val ipAddress: String? = null,

    @Column(name = "user_agent")
    val userAgent: String? = null,

    @Column(nullable = false)
    val success: Boolean = true,

    @Column(name = "failure_reason")
    val failureReason: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()
)
