package com.shifa.domain

import jakarta.persistence.*
import java.time.LocalDate
import java.time.OffsetDateTime

@Entity
@Table(
    name = "ai_usage_counter",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_ai_usage_user_date", columnNames = ["user_id", "usage_date"])
    ]
)
class AiUsageCounter(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val role: Role,

    @Column(name = "usage_date", nullable = false)
    val usageDate: LocalDate,

    @Column(name = "request_count", nullable = false)
    var requestCount: Int = 0,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
