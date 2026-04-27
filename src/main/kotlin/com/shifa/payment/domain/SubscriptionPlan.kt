package com.shifa.payment.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "subscription_plans")
class SubscriptionPlan(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true, length = 64)
    val code: String,

    @Column(nullable = false, length = 120)
    var name: String,

    @Column(name = "price_minor", nullable = false)
    var priceMinor: Long,

    @Column(nullable = false, length = 3)
    var currency: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var interval: SubscriptionInterval,

    @Column(name = "features_json", columnDefinition = "TEXT")
    var featuresJson: String? = null,

    @Column(name = "stripe_price_id")
    var stripePriceId: String? = null,

    @Column(name = "click_plan_id")
    var clickPlanId: String? = null,

    @Column(name = "payme_plan_id")
    var paymePlanId: String? = null,

    @Column(nullable = false)
    var enabled: Boolean = true,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)
