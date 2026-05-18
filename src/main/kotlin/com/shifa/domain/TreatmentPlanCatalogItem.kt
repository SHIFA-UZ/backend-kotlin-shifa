package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "treatment_plan_catalog_items")
class TreatmentPlanCatalogItem(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clinic_id", nullable = false)
    val clinic: Clinic,

    @Column(length = 64)
    var code: String? = null,

    @Column(nullable = false)
    var title: String,

    @Column(name = "default_price_minor", nullable = false)
    var defaultPriceMinor: Long = 0,

    @Column(nullable = false, length = 3)
    var currency: String = "UZS",

    @Column(name = "vat_percent")
    var vatPercent: java.math.BigDecimal? = null,

    @Column(nullable = false)
    var active: Boolean = true,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @Column(name = "applies_to_all_doctors", nullable = false)
    var appliesToAllDoctors: Boolean = true,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
