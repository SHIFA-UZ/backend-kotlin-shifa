package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "treatment_plans")
class TreatmentPlan(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clinic_id", nullable = false)
    val clinic: Clinic,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    val patient: PatientProfile,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attending_doctor_id")
    var attendingDoctor: DoctorProfile? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: Status = Status.DRAFT,

    var title: String? = null,

    @Column(columnDefinition = "TEXT")
    var diagnosis: String? = null,

    @Column(columnDefinition = "TEXT")
    var notes: String? = null,

    @Column(name = "payment_reminder_days")
    var paymentReminderDays: Int? = null,

    @Column(name = "estimated_total_minor", nullable = false)
    var estimatedTotalMinor: Long = 0,

    @Column(name = "actual_total_minor", nullable = false)
    var actualTotalMinor: Long = 0,

    @Column(name = "paid_amount_minor", nullable = false)
    var paidAmountMinor: Long = 0,

    @Column(name = "remaining_amount_minor", nullable = false)
    var remainingAmountMinor: Long = 0,

    @Column(name = "last_payment_reminder_sent_at")
    var lastPaymentReminderSentAt: OffsetDateTime? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    var createdByUser: User? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
) {
    enum class Status {
        DRAFT,
        ACTIVE,
        ON_HOLD,
        IN_PROGRESS,
        COMPLETED,
        CANCELLED
    }
}
