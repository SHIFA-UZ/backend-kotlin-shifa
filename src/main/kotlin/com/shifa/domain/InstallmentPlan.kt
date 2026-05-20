package com.shifa.domain

import jakarta.persistence.*
import java.time.LocalDate
import java.time.OffsetDateTime

@Entity
@Table(name = "installment_plans")
class InstallmentPlan(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "treatment_plan_id", nullable = false)
    val treatmentPlan: TreatmentPlan,

    @Column(name = "total_amount_minor", nullable = false)
    var totalAmountMinor: Long,

    @Column(nullable = false, length = 3)
    var currency: String = "UZS",

    @Column(name = "num_installments", nullable = false)
    var numInstallments: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var frequency: Frequency = Frequency.MONTHLY,

    @Column(name = "start_date", nullable = false)
    var startDate: LocalDate,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: Status = Status.ACTIVE,

    @Column(columnDefinition = "TEXT")
    var notes: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    var createdByUser: User? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
) {
    enum class Frequency {
        WEEKLY,
        BIWEEKLY,
        MONTHLY,
        CUSTOM
    }

    enum class Status {
        ACTIVE,
        PAUSED,
        COMPLETED,
        CANCELLED,
        DEFAULTED
    }
}
