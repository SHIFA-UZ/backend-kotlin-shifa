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
    var sortOrder: Int = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_doctor_id")
    var assignedDoctor: DoctorProfile? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_appointment_id")
    var linkedAppointment: Appointment? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: LineStatus = LineStatus.PLANNED,

    @Column(name = "specialty_metadata", columnDefinition = "TEXT")
    var specialtyMetadata: String? = null,

    @Column(columnDefinition = "TEXT")
    var notes: String? = null
) {
    enum class LineStatus {
        PLANNED,
        SCHEDULED,
        IN_PROGRESS,
        COMPLETED,
        CANCELLED
    }
}
