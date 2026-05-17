package com.shifa.domain

import jakarta.persistence.*

@Entity
@Table(name = "treatment_plan_lines")
class TreatmentPlanLine(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    val plan: TreatmentPlan,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "catalog_item_id")
    var catalogItem: TreatmentPlanCatalogItem? = null,

    @Column(nullable = false)
    var title: String,

    @Column(nullable = false)
    var quantity: Int = 1,

    @Column(name = "unit_price_minor", nullable = false)
    var unitPriceMinor: Long,

    @Column(name = "discount_minor", nullable = false)
    var discountMinor: Long = 0,

    @Column(nullable = false, length = 3)
    var currency: String = "UZS",

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0
)
