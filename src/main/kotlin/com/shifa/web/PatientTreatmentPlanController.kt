package com.shifa.web

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
) {

    data class PatientPlanSummaryDto(
        val id: Long,
        val status: String,
        val notes: String?,
        val totalMinor: Long,
        val paidMinor: Long,
        val owedMinor: Long,
        val currency: String
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
        @PathVariable planId: Long
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
        return PatientPlanSummaryDto(
            id = plan.id,
            status = plan.status.name,
            notes = plan.notes,
            totalMinor = total,
            paidMinor = paid,
            owedMinor = owed,
            currency = currency
        )
    }
}
