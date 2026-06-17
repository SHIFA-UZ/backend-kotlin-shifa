package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "treatment_plan_line_fulfillments")
class TreatmentPlanLineFulfillment(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "line_id", nullable = false, unique = true)
    val line: TreatmentPlanLine,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id", nullable = false)
    val appointment: Appointment,

    @Column(name = "completed_at", nullable = false)
    val completedAt: OffsetDateTime = OffsetDateTime.now(),
)
