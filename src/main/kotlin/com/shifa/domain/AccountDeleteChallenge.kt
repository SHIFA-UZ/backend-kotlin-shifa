package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "account_delete_challenges")
open class AccountDeleteChallenge(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,

    @Column(name = "challenge_id", nullable = false, unique = true)
    var challengeId: UUID = UUID.randomUUID(),

    @Column(name = "expires_at", nullable = false)
    var expiresAt: OffsetDateTime,

    @Column(name = "used", nullable = false)
    var used: Boolean = false,

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "used_at")
    var usedAt: OffsetDateTime? = null,
)

