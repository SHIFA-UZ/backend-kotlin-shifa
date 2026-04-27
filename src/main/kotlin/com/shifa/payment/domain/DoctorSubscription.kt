package com.shifa.payment.domain

import com.shifa.domain.DoctorProfile
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "doctor_subscriptions")
class DoctorSubscription(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    val doctor: DoctorProfile,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    var plan: SubscriptionPlan,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var gateway: PaymentGatewayCode,

    @Column(name = "external_subscription_id")
    var externalSubscriptionId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: DoctorSubscriptionStatus = DoctorSubscriptionStatus.TRIALING,

    @Column(name = "trial_ends_at")
    var trialEndsAt: Instant? = null,

    @Column(name = "current_period_end")
    var currentPeriodEnd: Instant? = null,

    @Column(name = "grace_period_ends_at")
    var gracePeriodEndsAt: Instant? = null,

    @Column(name = "suspended_at")
    var suspendedAt: Instant? = null,

    @Column(name = "cancel_at_period_end", nullable = false)
    var cancelAtPeriodEnd: Boolean = false,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)
