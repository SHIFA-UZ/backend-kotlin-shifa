package com.shifa.domain

import jakarta.persistence.*

@Entity
@Table(
    name = "treatment_plan_catalog_item_doctors",
    uniqueConstraints = [UniqueConstraint(columnNames = ["catalog_item_id", "doctor_profile_id"])]
)
class TreatmentPlanCatalogItemDoctor(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "catalog_item_id", nullable = false)
    val catalogItem: TreatmentPlanCatalogItem,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_profile_id", nullable = false)
    val doctor: DoctorProfile,
)
