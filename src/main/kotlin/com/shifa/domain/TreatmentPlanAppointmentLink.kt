package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "treatment_plan_appointment_links")
class TreatmentPlanAppointmentLink(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    val plan: TreatmentPlan,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id", nullable = false)
    val appointment: Appointment,

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_mode", nullable = false, length = 32)
    val billingMode: BillingMode,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    val createdByUser: User? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
) {
    enum class BillingMode {
        FULFILL_PLANNED,
        ADD_EXTRA,
    }
}
