package com.shifa.service

import com.shifa.domain.FinancialRecord
import com.shifa.domain.TreatmentPlan
import com.shifa.domain.TreatmentPlanPayment
import com.shifa.domain.User
import com.shifa.repo.FinancialRecordRepository
import com.shifa.repo.TreatmentPlanLineRepository
import com.shifa.repo.TreatmentPlanPaymentRepository
import com.shifa.repo.TreatmentPlanRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.OffsetDateTime

@Service
class TreatmentPlanFinanceService(
    private val plans: TreatmentPlanRepository,
    private val linesRepo: TreatmentPlanLineRepository,
    private val paymentsRepo: TreatmentPlanPaymentRepository,
    private val financialRecords: FinancialRecordRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun recalculatePlanTotals(planId: Long): TreatmentPlan {
        val plan = plans.findById(planId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found")
        }
        val lines = linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(planId)
        val estimated = lines.sumOf { (it.unitPriceMinor * it.quantity - it.discountMinor).coerceAtLeast(0) }
        val paid = paymentsRepo.findByPlan_IdOrderByRecordedAtAsc(planId).sumOf { it.amountMinor }

        plan.estimatedTotalMinor = estimated
        plan.actualTotalMinor = estimated
        plan.paidAmountMinor = paid
        plan.remainingAmountMinor = (estimated - paid).coerceAtLeast(0)
        plan.updatedAt = OffsetDateTime.now()

        return plans.save(plan)
    }

    @Transactional
    fun recordPaymentAndSync(
        planId: Long,
        amountMinor: Long,
        currency: String,
        method: TreatmentPlanPayment.PaymentMethod,
        memo: String?,
        recordedByUser: User?,
        financialRecord: FinancialRecord? = null
    ): TreatmentPlanPayment {
        val plan = plans.findById(planId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found")
        }
        val payment = paymentsRepo.save(
            TreatmentPlanPayment(
                plan = plan,
                amountMinor = amountMinor,
                currency = currency,
                method = method,
                memo = memo,
                financialRecord = financialRecord,
                recordedByUser = recordedByUser
            )
        )
        recalculatePlanTotals(planId)

        financialRecord?.let { fr ->
            fr.paidMinor += amountMinor
            fr.remainingMinor = (fr.totalMinor - fr.paidMinor).coerceAtLeast(0)
            fr.status = when {
                fr.remainingMinor <= 0 -> FinancialRecord.Status.PAID
                fr.paidMinor > 0 -> FinancialRecord.Status.PARTIALLY_PAID
                else -> fr.status
            }
            fr.updatedAt = OffsetDateTime.now()
            financialRecords.save(fr)
        }

        // Best-effort: mirror each plan payment into a RECEIPT doc so Finance →
        // Records is never empty purely because nobody created manual records.
        if (financialRecord == null && amountMinor > 0) {
            try {
                val notes = mutableListOf("Treatment plan payment · ${method.name}")
                memo?.trim()?.takeIf { it.isNotEmpty() }?.let { notes += it }
                val receipt = FinancialRecord(
                    clinic = plan.clinic,
                    patient = plan.patient,
                    treatmentPlan = plan,
                    recordType = FinancialRecord.RecordType.RECEIPT,
                    status = FinancialRecord.Status.PAID,
                    subtotalMinor = amountMinor,
                    discountMinor = 0,
                    taxMinor = 0,
                    totalMinor = amountMinor,
                    paidMinor = amountMinor,
                    remainingMinor = 0,
                    currency = currency,
                    issuedAt = OffsetDateTime.now(),
                    notes = notes.joinToString(" · "),
                    createdByUser = recordedByUser,
                )
                val savedReceipt = financialRecords.save(receipt)
                payment.financialRecord = savedReceipt
                paymentsRepo.save(payment)
            } catch (e: Exception) {
                log.warn(
                    "Auto RECEIPT financial record skipped for TreatmentPlanPayment id={}: {} {}",
                    payment.id,
                    e.javaClass.simpleName,
                    e.message,
                )
            }
        }

        return payment
    }

    @Transactional
    fun generateFinancialRecord(
        planId: Long,
        recordType: FinancialRecord.RecordType,
        createdByUser: User?
    ): FinancialRecord {
        val plan = plans.findById(planId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found")
        }
        val lines = linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(planId)
        val subtotal = lines.sumOf { (it.unitPriceMinor * it.quantity).coerceAtLeast(0) }
        val discount = lines.sumOf { it.discountMinor.coerceAtLeast(0) }
        val total = (subtotal - discount).coerceAtLeast(0)
        val currency = lines.firstOrNull()?.currency ?: "UZS"

        val record = FinancialRecord(
            clinic = plan.clinic,
            patient = plan.patient,
            treatmentPlan = plan,
            recordType = recordType,
            status = FinancialRecord.Status.DRAFT,
            subtotalMinor = subtotal,
            discountMinor = discount,
            totalMinor = total,
            remainingMinor = total,
            currency = currency,
            createdByUser = createdByUser
        )
        return financialRecords.save(record)
    }

    /**
     * Creates a single ISSUED invoice for a newly created installment schedule
     * unless one already exists with the marker `Installment plan #…` (idempotent).
     */
    fun ensureInstallmentInvoice(
        treatmentPlan: TreatmentPlan,
        installmentPlanId: Long,
        totalScheduledMinor: Long,
        currency: String,
        firstDueDate: LocalDate?,
        createdByUser: User?,
    ) {
        val marker = "Installment plan #$installmentPlanId"
        val existing = financialRecords.findByTreatmentPlan_IdOrderByCreatedAtDesc(treatmentPlan.id)
            .find { fr ->
                fr.recordType == FinancialRecord.RecordType.INVOICE &&
                    fr.notes?.contains(marker, ignoreCase = false) == true
            }
        if (existing != null) return
        if (totalScheduledMinor <= 0L) return
        val invoice = FinancialRecord(
            clinic = treatmentPlan.clinic,
            patient = treatmentPlan.patient,
            treatmentPlan = treatmentPlan,
            recordType = FinancialRecord.RecordType.INVOICE,
            status = FinancialRecord.Status.ISSUED,
            subtotalMinor = totalScheduledMinor,
            discountMinor = 0,
            taxMinor = 0,
            totalMinor = totalScheduledMinor,
            paidMinor = 0,
            remainingMinor = totalScheduledMinor,
            currency = currency,
            dueDate = firstDueDate,
            issuedAt = OffsetDateTime.now(),
            notes = marker,
            createdByUser = createdByUser,
        )
        financialRecords.save(invoice)
    }
}
