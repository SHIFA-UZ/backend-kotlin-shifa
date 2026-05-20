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

    data class CreateInstallmentRequest(
        val totalAmountMinor: Long,
        val currency: String = "UZS",
        val numInstallments: Int,
        val frequency: InstallmentPlan.Frequency = InstallmentPlan.Frequency.MONTHLY,
        val startDate: LocalDate,
        val notes: String? = null
    )

    @Transactional
    fun createInstallmentPlan(
        treatmentPlanId: Long,
        request: CreateInstallmentRequest,
        createdByUser: User?
    ): InstallmentPlan {
        val treatmentPlan = plans.findById(treatmentPlanId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Treatment plan not found")
        }
        if (request.numInstallments < 2) {
            throw IllegalArgumentException("At least 2 installments required")
        }
        if (request.totalAmountMinor <= 0) {
            throw IllegalArgumentException("Total amount must be positive")
        }

        val plan = installmentPlans.save(
            InstallmentPlan(
                treatmentPlan = treatmentPlan,
                totalAmountMinor = request.totalAmountMinor,
                currency = request.currency,
                numInstallments = request.numInstallments,
                frequency = request.frequency,
                startDate = request.startDate,
                notes = request.notes,
                createdByUser = createdByUser
            )
        )

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
                    currency = request.currency
                )
            )
        }

        return plan
    }

    @Transactional
    fun markInstallmentPaid(
        installmentItemId: Long,
        payment: TreatmentPlanPayment
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
            listOf(InstallmentItem.Status.PENDING, InstallmentItem.Status.OVERDUE)
        )
        if (unpaid.isEmpty()) {
            plan.status = InstallmentPlan.Status.COMPLETED
            plan.updatedAt = OffsetDateTime.now()
            installmentPlans.save(plan)
        }

        return item
    }

    fun getOverdueItems(clinicId: Long): List<InstallmentItem> {
        return installmentItems.findOverdueByClinic(clinicId, InstallmentItem.Status.PENDING, LocalDate.now())
    }

    private fun calculateDueDate(
        startDate: LocalDate,
        frequency: InstallmentPlan.Frequency,
        offset: Int
    ): LocalDate = when (frequency) {
        InstallmentPlan.Frequency.WEEKLY -> startDate.plusWeeks(offset.toLong())
        InstallmentPlan.Frequency.BIWEEKLY -> startDate.plusWeeks(offset.toLong() * 2)
        InstallmentPlan.Frequency.MONTHLY -> startDate.plusMonths(offset.toLong())
        InstallmentPlan.Frequency.CUSTOM -> startDate.plusMonths(offset.toLong())
    }
}
