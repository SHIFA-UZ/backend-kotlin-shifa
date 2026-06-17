package com.shifa.web

import com.shifa.domain.TreatmentPlanLine
import com.shifa.repo.InstallmentItemRepository
import com.shifa.repo.InstallmentPlanRepository
import com.shifa.repo.TreatmentPlanLineRepository
import com.shifa.repo.TreatmentPlanPaymentRepository
import com.shifa.repo.TreatmentPlanRepository
import com.shifa.security.PatientPrincipal
import com.shifa.repo.PatientProfileRepository
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/patients/me/treatment-plans")
class PatientTreatmentPlanController(
    private val patientProfiles: PatientProfileRepository,
    private val plans: TreatmentPlanRepository,
    private val linesRepo: TreatmentPlanLineRepository,
    private val paymentsRepo: TreatmentPlanPaymentRepository,
    private val installmentPlans: InstallmentPlanRepository,
    private val installmentItems: InstallmentItemRepository,
) {

    data class PatientPlanLineDto(
        val title: String,
        val quantity: Int,
        val unitPriceMinor: Long,
        val discountMinor: Long,
        val lineTotalMinor: Long,
        val currency: String,
        val status: String,
    )

    data class PatientInstallmentItemDto(
        val sequenceNumber: Int,
        val dueDate: String,
        val amountMinor: Long,
        val currency: String,
        val status: String,
    )

    data class PatientInstallmentPlanDto(
        val id: Long,
        val status: String,
        val totalAmountMinor: Long,
        val currency: String,
        val items: List<PatientInstallmentItemDto>,
    )

    data class PatientPlanSummaryDto(
        val id: Long,
        val status: String,
        val title: String?,
        val diagnosis: String?,
        val notes: String?,
        val totalMinor: Long,
        val paidMinor: Long,
        val owedMinor: Long,
        val currency: String,
        val planPaymentStatus: String,
        val linesCompletedCount: Int,
        val linesTotalCount: Int,
        val lines: List<PatientPlanLineDto>,
        val installmentPlans: List<PatientInstallmentPlanDto>,
    )

    private fun currentPatient(principal: PatientPrincipal) =
        principal.user.phone?.let { patientProfiles.findByPhone(it) }
            ?.orElseGet {
                principal.user.email?.let { patientProfiles.findByEmail(it) }?.orElse(null)
            }
            ?: patientProfiles.findByUserId(principal.user.id).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Patient profile not found")
            }

    @GetMapping("/{planId}")
    @Transactional(readOnly = true)
    fun getMyPlan(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @PathVariable planId: Long,
    ): PatientPlanSummaryDto {
        val patient = currentPatient(principal)
        val pid = patient.id ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient profile not found")
        val plan = plans.findById(planId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found")
        }
        if (plan.patient.id != pid) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found")
        }
        val lineRows = linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(planId)
        val currency = lineRows.firstOrNull()?.currency ?: "UZS"
        val total = lineRows.sumOf { (it.unitPriceMinor * it.quantity - it.discountMinor).coerceAtLeast(0) }
        val paid = paymentsRepo.findByPlan_IdOrderByRecordedAtAsc(planId).sumOf { it.amountMinor }
        val owed = (total - paid).coerceAtLeast(0)
        val payStatus = when {
            total <= 0L -> "NONE"
            paid <= 0L -> "UNPAID"
            paid >= total -> "PAID"
            else -> "PARTIAL"
        }
        val lines = lineRows.map {
            PatientPlanLineDto(
                title = it.title,
                quantity = it.quantity,
                unitPriceMinor = it.unitPriceMinor,
                discountMinor = it.discountMinor,
                lineTotalMinor = (it.unitPriceMinor * it.quantity - it.discountMinor).coerceAtLeast(0),
                currency = it.currency,
                status = it.status.name,
            )
        }
        val instPlans = installmentPlans.findByTreatmentPlan_IdOrderByCreatedAtDesc(planId).map { ip ->
            val items = installmentItems.findByInstallmentPlan_IdOrderBySequenceNumberAsc(ip.id)
            PatientInstallmentPlanDto(
                id = ip.id,
                status = ip.status.name,
                totalAmountMinor = ip.totalAmountMinor,
                currency = ip.currency,
                items = items.map { ii ->
                    PatientInstallmentItemDto(
                        sequenceNumber = ii.sequenceNumber,
                        dueDate = ii.dueDate.toString(),
                        amountMinor = ii.amountMinor,
                        currency = ii.currency,
                        status = ii.status.name,
                    )
                },
            )
        }
        return PatientPlanSummaryDto(
            id = plan.id,
            status = plan.status.name,
            title = plan.title,
            diagnosis = plan.diagnosis,
            notes = plan.notes,
            totalMinor = total,
            paidMinor = paid,
            owedMinor = owed,
            currency = currency,
            planPaymentStatus = payStatus,
            linesCompletedCount = lineRows.count {
                it.status == TreatmentPlanLine.LineStatus.COMPLETED
            },
            linesTotalCount = lineRows.size,
            lines = lines,
            installmentPlans = instPlans,
        )
    }
}
