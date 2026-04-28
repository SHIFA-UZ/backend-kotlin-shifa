package com.shifa.payment.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(
    name = "payment_events",
    uniqueConstraints = [UniqueConstraint(name = "ux_payment_events_gateway_event", columnNames = ["gateway", "event_id"])]
)
class PaymentEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val gateway: PaymentGatewayCode,

    @Column(name = "event_id", nullable = false, length = 255)
    val eventId: String,

    @Column(name = "event_type", nullable = false, length = 128)
    val eventType: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    var payment: Payment? = null,

    @Column(nullable = false, columnDefinition = "TEXT")
    val payload: String,

    @Column(nullable = false)
    var processed: Boolean = false,

    @Column(name = "processed_at")
    var processedAt: Instant? = null,

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    var failureReason: String? = null,

    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0,

    @Column(name = "last_retry_at")
    var lastRetryAt: Instant? = null,

    @Column(name = "retried_by_admin_user_id")
    var retriedByAdminUserId: Long? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)
