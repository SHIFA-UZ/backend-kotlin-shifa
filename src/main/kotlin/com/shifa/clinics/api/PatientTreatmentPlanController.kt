package com.shifa.clinics.api

import com.shifa.clinics.api.dto.PatientInstallmentItemDto
import com.shifa.clinics.api.dto.PatientInstallmentPlanDto
import com.shifa.clinics.api.dto.PatientPlanLineDto
import com.shifa.clinics.api.dto.PatientPlanSummaryDto
import com.shifa.clinics.domain.TreatmentPlanLine
import com.shifa.shared.security.PatientPrincipal
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
    private val patientProfiles: com.shifa.patients.application.PatientProfiles,
    private val plans: com.shifa.clinics.application.TreatmentPlans,
    private val linesRepo: com.shifa.clinics.application.TreatmentPlanLines,
    private val paymentsRepo: com.shifa.clinics.application.TreatmentPlanPayments,
    private val installmentPlans: com.shifa.clinics.application.InstallmentPlans,
    private val installmentItems: com.shifa.clinics.application.InstallmentItems,
    private val currentPatient: com.shifa.patients.application.CurrentPatientResolver,
) {

    private fun currentPatient(principal: PatientPrincipal) = currentPatient.resolve(principal)
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
                        id = ii.id,
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
