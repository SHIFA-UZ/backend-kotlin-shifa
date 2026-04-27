package com.shifa.payment.domain

import com.shifa.domain.User
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "refunds")
class Refund(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    val payment: Payment,

    @Column(name = "amount_minor", nullable = false)
    val amountMinor: Long,

    @Column(columnDefinition = "TEXT")
    val reason: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: RefundStatus = RefundStatus.PENDING,

    @Column(name = "gateway_refund_id")
    var gatewayRefundId: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "initiated_by_user_id")
    val initiatedByUser: User? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)
