package com.shifa.domain

import jakarta.persistence.*
import java.time.LocalDate
import java.time.OffsetDateTime

@Entity
@Table(name = "financial_records")
class FinancialRecord(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clinic_id", nullable = false)
    val clinic: Clinic,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    val patient: PatientProfile,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "treatment_plan_id")
    var treatmentPlan: TreatmentPlan? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "record_type", nullable = false, length = 32)
    val recordType: RecordType,

    @Column(name = "record_number", length = 64)
    var recordNumber: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: Status = Status.DRAFT,

    @Column(name = "subtotal_minor", nullable = false)
    var subtotalMinor: Long = 0,

    @Column(name = "discount_minor", nullable = false)
    var discountMinor: Long = 0,

    @Column(name = "tax_minor", nullable = false)
    var taxMinor: Long = 0,

    @Column(name = "total_minor", nullable = false)
    var totalMinor: Long = 0,

    @Column(name = "paid_minor", nullable = false)
    var paidMinor: Long = 0,

    @Column(name = "remaining_minor", nullable = false)
    var remainingMinor: Long = 0,

    @Column(nullable = false, length = 3)
    var currency: String = "UZS",

    @Column(name = "issued_at")
    var issuedAt: OffsetDateTime? = null,

    @Column(name = "due_date")
    var dueDate: LocalDate? = null,

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
    enum class RecordType {
        INVOICE,
        CREDIT_NOTE,
        RECEIPT,
        ESTIMATE
    }

    enum class Status {
        DRAFT,
        ISSUED,
        PARTIALLY_PAID,
        PAID,
        OVERDUE,
        CANCELLED,
        VOIDED
    }
}
