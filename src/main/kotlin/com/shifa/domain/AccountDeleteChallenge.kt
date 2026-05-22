package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "account_delete_challenges")
open class AccountDeleteChallenge(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    open var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    open var user: User,

    @Column(name = "challenge_id", nullable = false, unique = true)
    open var challengeId: UUID = UUID.randomUUID(),

    @Column(name = "expires_at", nullable = false)
    open var expiresAt: OffsetDateTime,

    @Column(name = "used", nullable = false)
    open var used: Boolean = false,

    @Column(name = "attempt_count", nullable = false)
    open var attemptCount: Int = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    open var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "used_at")
    open var usedAt: OffsetDateTime? = null,
)

