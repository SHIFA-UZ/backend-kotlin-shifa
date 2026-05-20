package com.shifa.service

import com.shifa.domain.InstallmentItem
import com.shifa.domain.InstallmentPlan
import com.shifa.domain.TreatmentPlanPayment
import com.shifa.domain.User
import com.shifa.repo.InstallmentItemRepository
import com.shifa.repo.InstallmentPlanRepository
import com.shifa.repo.TreatmentPlanRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.OffsetDateTime

@Service
class InstallmentService(
    private val plans: TreatmentPlanRepository,
    private val installmentPlans: InstallmentPlanRepository,
    private val installmentItems: InstallmentItemRepository,
) {

    data class InstallmentScheduleItem(val dueDate: LocalDate, val amountMinor: Long)

    data class CreateInstallmentRequest(
        val totalAmountMinor: Long,
        val currency: String = "UZS",
        val numInstallments: Int,
        val frequency: InstallmentPlan.Frequency = InstallmentPlan.Frequency.MONTHLY,
        val startDate: LocalDate,
        val notes: String? = null,
        val scheduleItems: List<InstallmentScheduleItem>? = null,
    )

    @Transactional
    fun createInstallmentPlan(
        treatmentPlanId: Long,
        request: CreateInstallmentRequest,
        createdByUser: User?,
    ): InstallmentPlan {
        val treatmentPlan = plans.findById(treatmentPlanId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Treatment plan not found")
        }
        if (request.totalAmountMinor <= 0) {
            throw IllegalArgumentException("Total amount must be positive")
        }

        val custom = request.scheduleItems?.takeIf { it.isNotEmpty() }
        val effectiveCount = custom?.size ?: request.numInstallments
        if (effectiveCount < 2) {
            throw IllegalArgumentException("At least 2 installments required")
        }

        if (custom != null) {
            val sum = custom.sumOf { it.amountMinor }
            if (sum != request.totalAmountMinor) {
                throw IllegalArgumentException("Custom installment amounts must sum to totalAmountMinor")
            }
        }

        val plan = installmentPlans.save(
            InstallmentPlan(
                treatmentPlan = treatmentPlan,
                totalAmountMinor = request.totalAmountMinor,
                currency = request.currency,
                numInstallments = effectiveCount,
                frequency = if (custom != null) InstallmentPlan.Frequency.CUSTOM else request.frequency,
                startDate = custom?.firstOrNull()?.dueDate ?: request.startDate,
                notes = request.notes,
                createdByUser = createdByUser,
            ),
        )

        if (custom != null) {
            custom.forEachIndexed { index, row ->
                installmentItems.save(
                    InstallmentItem(
                        installmentPlan = plan,
                        sequenceNumber = index + 1,
                        dueDate = row.dueDate,
                        amountMinor = row.amountMinor,
                        currency = request.currency,
                    ),
                )
            }
        } else {
            val baseAmount = request.totalAmountMinor / request.numInstallments
            val remainder = request.totalAmountMinor - (baseAmount * request.numInstallments)
            for (i in 1..request.numInstallments) {
                val dueDate = calculateDueDate(request.startDate, request.frequency, i - 1)
                val amount = if (i == request.numInstallments) baseAmount + remainder else baseAmount
                installmentItems.save(
                    InstallmentItem(
                        installmentPlan = plan,
                        sequenceNumber = i,
                        dueDate = dueDate,
                        amountMinor = amount,
                        currency = request.currency,
                    ),
                )
            }
        }

        return plan
    }

    @Transactional
    fun markInstallmentPaid(
        installmentItemId: Long,
        payment: TreatmentPlanPayment,
    ): InstallmentItem {
        val item = installmentItems.findById(installmentItemId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Installment item not found")
        }
        item.status = InstallmentItem.Status.PAID
        item.paidAt = OffsetDateTime.now()
        item.payment = payment
        installmentItems.save(item)

        val plan = item.installmentPlan
        val unpaid = installmentItems.findUnpaidByPlan(
            plan.id,
            listOf(InstallmentItem.Status.PENDING, InstallmentItem.Status.OVERDUE),
        )
        if (unpaid.isEmpty()) {
            plan.status = InstallmentPlan.Status.COMPLETED
            plan.updatedAt = OffsetDateTime.now()
            installmentPlans.save(plan)
        }

        return item
    }

    @Transactional
    fun markPendingInstallmentsOverdue(today: LocalDate): Int {
        val past = installmentItems.findAllPendingPastDue(today)
        var n = 0
        for (item in past) {
            item.status = InstallmentItem.Status.OVERDUE
            installmentItems.save(item)
            n++
        }
        return n
    }

    fun getOverdueItems(clinicId: Long): List<InstallmentItem> {
        return installmentItems.findOverdueByClinic(clinicId, LocalDate.now())
    }

    private fun calculateDueDate(
        startDate: LocalDate,
        frequency: InstallmentPlan.Frequency,
        offset: Int,
    ): LocalDate = when (frequency) {
        InstallmentPlan.Frequency.WEEKLY -> startDate.plusWeeks(offset.toLong())
        InstallmentPlan.Frequency.BIWEEKLY -> startDate.plusWeeks(offset.toLong() * 2)
        InstallmentPlan.Frequency.MONTHLY -> startDate.plusMonths(offset.toLong())
        InstallmentPlan.Frequency.CUSTOM -> startDate.plusMonths(offset.toLong())
    }
}