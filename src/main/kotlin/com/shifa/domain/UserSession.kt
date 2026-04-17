package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "user_sessions")
class UserSession(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(name = "token_jti", unique = true)
    val tokenJti: String? = null, // JWT ID claim

    @Column(name = "ip_address")
    val ipAddress: String? = null,

    @Column(name = "user_agent")
    val userAgent: String? = null,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: OffsetDateTime,

    @Column(nullable = false)
    var revoked: Boolean = false,

    @Column(name = "revoked_at")
    var revokedAt: OffsetDateTime? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revoked_by_user_id")
    val revokedBy: User? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()
)
