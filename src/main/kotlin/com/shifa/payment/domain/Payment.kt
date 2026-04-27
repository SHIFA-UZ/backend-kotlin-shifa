package com.shifa.payment.domain

import com.shifa.domain.Appointment
import com.shifa.domain.DoctorProfile
import com.shifa.domain.User
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "payments")
class Payment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "external_ref", nullable = false, unique = true, length = 64)
    val externalRef: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val gateway: PaymentGatewayCode,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val kind: PaymentKind,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: PaymentStatus = PaymentStatus.PENDING,

    @Column(name = "amount_minor", nullable = false)
    val amountMinor: Long,

    @Column(nullable = false, length = 3)
    val currency: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id")
    var appointment: Appointment? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payer_user_id")
    val payerUser: User? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payee_doctor_id")
    val payeeDoctor: DoctorProfile? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id")
    var subscription: DoctorSubscription? = null,

    @Column(name = "gateway_payment_id")
    var gatewayPaymentId: String? = null,

    @Column(name = "gateway_checkout_url", columnDefinition = "TEXT")
    var gatewayCheckoutUrl: String? = null,

    @Column(name = "paid_at")
    var paidAt: Instant? = null,

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    var failureReason: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)
