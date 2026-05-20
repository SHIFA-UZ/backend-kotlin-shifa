package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "treatment_plan_payments")
class TreatmentPlanPayment(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    val plan: TreatmentPlan,

    @Column(name = "amount_minor", nullable = false)
    var amountMinor: Long,

    @Column(nullable = false, length = 3)
    var currency: String = "UZS",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var method: PaymentMethod,

    @Column(columnDefinition = "TEXT")
    var memo: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "financial_record_id")
    var financialRecord: FinancialRecord? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_user_id")
    var recordedByUser: User? = null,

    @Column(name = "recorded_at", nullable = false)
    val recordedAt: OffsetDateTime = OffsetDateTime.now()
) {
    enum class PaymentMethod {
        CASH,
        CARD_EXTERNAL,
        TRANSFER,
        WAIVED,
        OTHER
    }
}
