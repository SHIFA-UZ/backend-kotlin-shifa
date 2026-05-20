package com.shifa.domain

import jakarta.persistence.*
import java.time.LocalDate
import java.time.OffsetDateTime

@Entity
@Table(name = "installment_items")
class InstallmentItem(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "installment_plan_id", nullable = false)
    val installmentPlan: InstallmentPlan,

    @Column(name = "sequence_number", nullable = false)
    var sequenceNumber: Int,

    @Column(name = "due_date", nullable = false)
    var dueDate: LocalDate,

    @Column(name = "amount_minor", nullable = false)
    var amountMinor: Long,

    @Column(nullable = false, length = 3)
    var currency: String = "UZS",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: Status = Status.PENDING,

    @Column(name = "paid_at")
    var paidAt: OffsetDateTime? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    var payment: TreatmentPlanPayment? = null,

    @Column(columnDefinition = "TEXT")
    var notes: String? = null
) {
    enum class Status {
        PENDING,
        PAID,
        OVERDUE,
        WAIVED,
        CANCELLED
    }
}
