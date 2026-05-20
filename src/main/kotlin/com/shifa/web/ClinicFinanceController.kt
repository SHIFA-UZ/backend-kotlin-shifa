package com.shifa.web

import com.shifa.domain.FinancialRecord
import com.shifa.domain.InstallmentItem
import com.shifa.domain.InstallmentPlan
import com.shifa.domain.TreatmentPlanPayment
import com.shifa.repo.*
import com.shifa.service.*
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.OffsetDateTime

@RestController
@RequestMapping("/api/clinics/{clinicId}/finance")
class ClinicFinanceController(
    private val clinicAccess: ClinicAccessService,
    private val financeAccess: ClinicFinanceAccessService,
    private val financeService: TreatmentPlanFinanceService,
    private val installmentService: InstallmentService,
    private val auditService: ClinicFinanceAuditService,
    private val financialRecords: FinancialRecordRepository,
    private val treatmentPlans: TreatmentPlanRepository,
    private val treatmentPlanPayments: TreatmentPlanPaymentRepository,
    private val installmentPlans: InstallmentPlanRepository,
    private val installmentItems: InstallmentItemRepository,
    private val clinics: ClinicRepository,
    private val patients: PatientProfileRepository,
    private val users: UserRepository,
) {

    // --- DTOs ---

    data class FinanceDashboardDto(
        val totalRevenueMinor: Long,
        val outstandingMinor: Long,
        val overdueCount: Int,
        val collectionRate: Double,
        val currency: String
    )

    data class FinancialRecordDto(
        val id: Long,
        val clinicId: Long,
        val patientId: Long,
        val treatmentPlanId: Long?,
        val recordType: String,
        val recordNumber: String?,
        val status: String,
        val subtotalMinor: Long,
        val discountMinor: Long,
        val taxMinor: Long,
        val totalMinor: Long,
        val paidMinor: Long,
        val remainingMinor: Long,
        val currency: String,
        val issuedAt: OffsetDateTime?,
        val dueDate: LocalDate?,
        val notes: String?,
        val createdAt: OffsetDateTime
    )

    data class CreateFinancialRecordRequest(
        @field:NotNull val patientId: Long,
        val treatmentPlanId: Long?,
        @field:NotBlank val recordType: String,
        val recordNumber: String?,
        @field:Min(0) val subtotalMinor: Long = 0,
        @field:Min(0) val discountMinor: Long = 0,
        @field:Min(0) val taxMinor: Long = 0,
        val currency: String = "UZS",
        val dueDate: LocalDate?,
        val notes: String?
    )

    data class PatchFinancialRecordRequest(
        val status: String? = null,
        val notes: String? = null,
        val recordNumber: String? = null,
        val dueDate: LocalDate? = null
    )

    data class RecordPaymentRequest(
        @field:NotNull val treatmentPlanId: Long,
        @field:Min(1) val amountMinor: Long,
        val currency: String = "UZS",
        @field:NotNull val method: TreatmentPlanPayment.PaymentMethod,
        val memo: String?,
        val financialRecordId: Long? = null
    )

    data class PaymentHistoryDto(
        val id: Long,
        val treatmentPlanId: Long,
        val amountMinor: Long,
        val currency: String,
        val method: String,
        val memo: String?,
        val financialRecordId: Long?,
        val recordedAt: OffsetDateTime
    )

    data class CreateInstallmentRequest(
        @field:NotNull val treatmentPlanId: Long,
        @field:Min(1) val totalAmountMinor: Long,
        val currency: String = "UZS",
        @field:Min(2) val numInstallments: Int,
        val frequency: String = "MONTHLY",
        @field:NotNull val startDate: LocalDate,
        val notes: String?
    )

    data class InstallmentPlanDto(
        val id: Long,
        val treatmentPlanId: Long,
        val totalAmountMinor: Long,
        val currency: String,
        val numInstallments: Int,
        val frequency: String,
        val startDate: LocalDate,
        val status: String,
        val notes: String?,
        val createdAt: OffsetDateTime,
        val items: List<InstallmentItemDto>
    )

    data class InstallmentItemDto(
        val id: Long,
        val sequenceNumber: Int,
        val dueDate: LocalDate,
        val amountMinor: Long,
        val currency: String,
        val status: String,
        val paidAt: OffsetDateTime?,
        val notes: String?
    )

    // --- Endpoints ---

    @GetMapping("/dashboard")
    @Transactional(readOnly = true)
    fun getDashboard(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long
    ): FinanceDashboardDto {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        financeAccess.assertCanViewFinance(principal, clinicId)

        val outstanding = financialRecords.findByClinicIdAndStatusInAndRemainingMinorGreaterThanZero(
            clinicId,
            listOf(FinancialRecord.Status.ISSUED, FinancialRecord.Status.PARTIALLY_PAID, FinancialRecord.Status.OVERDUE)
        )
        val outstandingTotal = outstanding.sumOf { it.remainingMinor }
        val overdueCount = outstanding.count { it.status == FinancialRecord.Status.OVERDUE }

        val allPlans = treatmentPlans.findByClinic_IdAndRemainingAmountMinorGreaterThan(clinicId, 0)
        val totalRevenue = allPlans.sumOf { it.paidAmountMinor }
        val totalExpected = allPlans.sumOf { it.estimatedTotalMinor }
        val collectionRate = if (totalExpected > 0) totalRevenue.toDouble() / totalExpected else 0.0

        return FinanceDashboardDto(
            totalRevenueMinor = totalRevenue,
            outstandingMinor = outstandingTotal,
            overdueCount = overdueCount,
            collectionRate = collectionRate,
            currency = "UZS"
        )
    }

    @GetMapping("/records")
    @Transactional(readOnly = true)
    fun listRecords(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int
    ): Map<String, Any> {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        financeAccess.assertCanViewFinance(principal, clinicId)

        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val result = if (status != null) {
            val s = FinancialRecord.Status.valueOf(status.uppercase())
            financialRecords.findByClinic_IdAndStatus(clinicId, s, pageable)
        } else {
            financialRecords.findByClinic_IdOrderByCreatedAtDesc(clinicId, pageable)
        }

        return mapOf(
            "content" to result.content.map { toRecordDto(it) },
            "totalElements" to result.totalElements,
            "totalPages" to result.totalPages,
            "number" to result.number
        )
    }

    @PostMapping("/records")
    @Transactional
    fun createRecord(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @RequestBody @Valid req: CreateFinancialRecordRequest
    ): FinancialRecordDto {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        financeAccess.assertCanManageInvoices(principal, clinicId)

        if (req.treatmentPlanId != null) {
            val uid = clinicAccess.resolveBookingActorUserId(principal)
            val user = users.findById(uid).orElse(null)
            val record = financeService.generateFinancialRecord(
                req.treatmentPlanId,
                FinancialRecord.RecordType.valueOf(req.recordType.uppercase()),
                user
            )
            req.recordNumber?.let { record.recordNumber = it.trim() }
            req.dueDate?.let { record.dueDate = it }
            req.notes?.let { record.notes = it.trim() }
            financialRecords.save(record)

            val clinic = clinics.findById(clinicId).orElseThrow()
            user?.let {
                auditService.log(clinic, it, "CREATE", "FINANCIAL_RECORD", record.id)
            }
            return toRecordDto(record)
        }

        val clinic = clinics.findById(clinicId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Clinic not found")
        }
        val patient = patients.findById(req.patientId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
        }
        val total = (req.subtotalMinor - req.discountMinor + req.taxMinor).coerceAtLeast(0)

        val uid = clinicAccess.resolveBookingActorUserId(principal)
        val user = users.findById(uid).orElse(null)

        val record = financialRecords.save(
            FinancialRecord(
                clinic = clinic,
                patient = patient,
                recordType = FinancialRecord.RecordType.valueOf(req.recordType.uppercase()),
                recordNumber = req.recordNumber?.trim(),
                subtotalMinor = req.subtotalMinor,
                discountMinor = req.discountMinor,
                taxMinor = req.taxMinor,
                totalMinor = total,
                remainingMinor = total,
                currency = req.currency,
                dueDate = req.dueDate,
                notes = req.notes?.trim(),
                createdByUser = user
            )
        )

        user?.let {
            auditService.log(clinic, it, "CREATE", "FINANCIAL_RECORD", record.id)
        }
        return toRecordDto(record)
    }

    @GetMapping("/records/{recordId}")
    @Transactional(readOnly = true)
    fun getRecord(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @PathVariable recordId: Long
    ): FinancialRecordDto {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        financeAccess.assertCanViewFinance(principal, clinicId)
        val record = financialRecords.findById(recordId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found")
        }
        if (record.clinic.id != clinicId) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return toRecordDto(record)
    }

    @PatchMapping("/records/{recordId}")
    @Transactional
    fun patchRecord(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @PathVariable recordId: Long,
        @RequestBody body: PatchFinancialRecordRequest
    ): FinancialRecordDto {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        financeAccess.assertCanManageInvoices(principal, clinicId)
        val record = financialRecords.findById(recordId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found")
        }
        if (record.clinic.id != clinicId) throw ResponseStatusException(HttpStatus.NOT_FOUND)

        body.status?.let {
            val newStatus = FinancialRecord.Status.valueOf(it.uppercase())
            if (newStatus == FinancialRecord.Status.VOIDED) {
                financeAccess.assertCanVoidRecords(principal, clinicId)
            }
            record.status = newStatus
        }
        body.notes?.let { record.notes = it.trim() }
        body.recordNumber?.let { record.recordNumber = it.trim() }
        body.dueDate?.let { record.dueDate = it }
        record.updatedAt = OffsetDateTime.now()
        financialRecords.save(record)

        return toRecordDto(record)
    }

    @PostMapping("/payments")
    @Transactional
    fun recordPayment(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @RequestBody @Valid req: RecordPaymentRequest
    ): PaymentHistoryDto {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        financeAccess.assertCanRecordPayment(principal, clinicId)

        val uid = clinicAccess.resolveBookingActorUserId(principal)
        val user = users.findById(uid).orElse(null)

        val fr = req.financialRecordId?.let {
            financialRecords.findById(it).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Financial record not found")
            }
        }

        val payment = financeService.recordPaymentAndSync(
            planId = req.treatmentPlanId,
            amountMinor = req.amountMinor,
            currency = req.currency,
            method = req.method,
            memo = req.memo?.trim(),
            recordedByUser = user,
            financialRecord = fr
        )

        val clinic = clinics.findById(clinicId).orElseThrow()
        user?.let {
            auditService.log(clinic, it, "RECORD_PAYMENT", "TREATMENT_PLAN_PAYMENT", payment.id)
        }

        return toPaymentDto(payment)
    }

    @GetMapping("/payments")
    @Transactional(readOnly = true)
    fun listPayments(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int
    ): List<PaymentHistoryDto> {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        financeAccess.assertCanViewFinance(principal, clinicId)

        val pageable = PageRequest.of(page, size)
        val payments = treatmentPlanPayments.findByClinicAndPlanStatuses(
            clinicId,
            listOf(
                com.shifa.domain.TreatmentPlan.Status.ACTIVE,
                com.shifa.domain.TreatmentPlan.Status.IN_PROGRESS,
                com.shifa.domain.TreatmentPlan.Status.COMPLETED
            ),
            pageable
        )
        return payments.map { toPaymentDto(it) }
    }

    @GetMapping("/overdue")
    @Transactional(readOnly = true)
    fun getOverdue(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long
    ): Map<String, Any> {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        financeAccess.assertCanViewFinance(principal, clinicId)

        val overdueRecords = financialRecords.findByClinicIdAndStatusInAndRemainingMinorGreaterThanZero(
            clinicId,
            listOf(FinancialRecord.Status.ISSUED, FinancialRecord.Status.PARTIALLY_PAID, FinancialRecord.Status.OVERDUE)
        )
            .filter { it.status == FinancialRecord.Status.OVERDUE }
            .map { toRecordDto(it) }

        val overdueInstallments = installmentService.getOverdueItems(clinicId)
            .map { toInstallmentItemDto(it) }

        return mapOf(
            "overdueRecords" to overdueRecords,
            "overdueInstallments" to overdueInstallments
        )
    }

    @PostMapping("/installment-plans")
    @Transactional
    fun createInstallmentPlan(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @RequestBody @Valid req: CreateInstallmentRequest
    ): InstallmentPlanDto {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        financeAccess.assertCanManageInstallments(principal, clinicId)

        val uid = clinicAccess.resolveBookingActorUserId(principal)
        val user = users.findById(uid).orElse(null)

        val plan = installmentService.createInstallmentPlan(
            treatmentPlanId = req.treatmentPlanId,
            request = InstallmentService.CreateInstallmentRequest(
                totalAmountMinor = req.totalAmountMinor,
                currency = req.currency,
                numInstallments = req.numInstallments,
                frequency = InstallmentPlan.Frequency.valueOf(req.frequency.uppercase()),
                startDate = req.startDate,
                notes = req.notes?.trim()
            ),
            createdByUser = user
        )

        val clinic = clinics.findById(clinicId).orElseThrow()
        user?.let {
            auditService.log(clinic, it, "CREATE", "INSTALLMENT_PLAN", plan.id)
        }

        val items = installmentItems.findByInstallmentPlan_IdOrderBySequenceNumberAsc(plan.id)
        return toInstallmentPlanDto(plan, items)
    }

    @GetMapping("/installment-plans/{planId}")
    @Transactional(readOnly = true)
    fun getInstallmentPlan(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @PathVariable planId: Long
    ): InstallmentPlanDto {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        financeAccess.assertCanViewFinance(principal, clinicId)

        val plan = installmentPlans.findById(planId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Installment plan not found")
        }
        val items = installmentItems.findByInstallmentPlan_IdOrderBySequenceNumberAsc(plan.id)
        return toInstallmentPlanDto(plan, items)
    }

    // --- Mappers ---

    private fun toRecordDto(r: FinancialRecord) = FinancialRecordDto(
        id = r.id,
        clinicId = r.clinic.id!!,
        patientId = r.patient.id!!,
        treatmentPlanId = r.treatmentPlan?.id,
        recordType = r.recordType.name,
        recordNumber = r.recordNumber,
        status = r.status.name,
        subtotalMinor = r.subtotalMinor,
        discountMinor = r.discountMinor,
        taxMinor = r.taxMinor,
        totalMinor = r.totalMinor,
        paidMinor = r.paidMinor,
        remainingMinor = r.remainingMinor,
        currency = r.currency,
        issuedAt = r.issuedAt,
        dueDate = r.dueDate,
        notes = r.notes,
        createdAt = r.createdAt
    )

    private fun toPaymentDto(p: TreatmentPlanPayment) = PaymentHistoryDto(
        id = p.id,
        treatmentPlanId = p.plan.id,
        amountMinor = p.amountMinor,
        currency = p.currency,
        method = p.method.name,
        memo = p.memo,
        financialRecordId = p.financialRecord?.id,
        recordedAt = p.recordedAt
    )

    private fun toInstallmentPlanDto(plan: InstallmentPlan, items: List<InstallmentItem>) = InstallmentPlanDto(
        id = plan.id,
        treatmentPlanId = plan.treatmentPlan.id,
        totalAmountMinor = plan.totalAmountMinor,
        currency = plan.currency,
        numInstallments = plan.numInstallments,
        frequency = plan.frequency.name,
        startDate = plan.startDate,
        status = plan.status.name,
        notes = plan.notes,
        createdAt = plan.createdAt,
        items = items.map { toInstallmentItemDto(it) }
    )

    private fun toInstallmentItemDto(item: InstallmentItem) = InstallmentItemDto(
        id = item.id,
        sequenceNumber = item.sequenceNumber,
        dueDate = item.dueDate,
        amountMinor = item.amountMinor,
        currency = item.currency,
        status = item.status.name,
        paidAt = item.paidAt,
        notes = item.notes
    )

}
