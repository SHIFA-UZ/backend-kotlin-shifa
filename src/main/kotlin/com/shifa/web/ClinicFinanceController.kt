package com.shifa.web

import com.shifa.domain.FinancialRecord
import com.shifa.domain.InstallmentItem
import com.shifa.domain.InstallmentPlan
import com.shifa.domain.Notification
import com.shifa.domain.TreatmentPlan
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
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset

@RestController
@RequestMapping("/api/clinics/{clinicId}/finance")
class ClinicFinanceController(
    private val clinicAccess: ClinicAccessService,
    private val financeAccess: ClinicFinanceAccessService,
    private val financeService: TreatmentPlanFinanceService,
    private val installmentService: InstallmentService,
    private val ledgerService: ClinicFinanceLedgerService,
    private val auditService: ClinicFinanceAuditService,
    private val financialRecords: FinancialRecordRepository,
    private val treatmentPlans: TreatmentPlanRepository,
    private val treatmentPlanPayments: TreatmentPlanPaymentRepository,
    private val installmentPlans: InstallmentPlanRepository,
    private val installmentItems: InstallmentItemRepository,
    private val linesRepo: TreatmentPlanLineRepository,
    private val appts: AppointmentRepository,
    private val clinics: ClinicRepository,
    private val patients: PatientProfileRepository,
    private val users: UserRepository,
    private val auditLogs: ClinicFinanceAuditLogRepository,
    private val notifications: NotificationRepository,
    private val fcmService: FcmService,
) {

    // --- DTOs ---

    data class FinanceDashboardDto(
        val totalRevenueMinor: Long,
        val outstandingMinor: Long,
        val overdueCount: Int,
        val collectionRate: Double,
        val currency: String,
        val doctorEarningsTop: List<DoctorEarningRowDto>,
    )

    data class DoctorEarningRowDto(
        val doctorProfileId: Long,
        val visitCount: Int,
        val grossMinor: Long,
        val collectedMinor: Long,
        val outstandingMinor: Long,
    )

    data class AppointmentLedgerRowDto(
        val appointmentId: Long,
        val startAt: String,
        val patientId: Long,
        val patientName: String,
        val doctorProfileId: Long,
        val doctorName: String,
        val treatmentPlanId: Long,
        val services: List<ServiceLineDto>,
        val visitTotalMinor: Long,
        val currency: String,
        val planPaymentStatus: String,
        val planSimplePaymentStatus: String,
    ) {
        data class ServiceLineDto(val title: String, val lineTotalMinor: Long)
    }

    data class FinancialRecordDto(
        val id: Long,
        val clinicId: Long,
        val patientId: Long,
        val treatmentPlanId: Long?,
        val recordType: String,
        val recordNumber: String?,
        val status: String,
        val uiPaymentStatus: String,
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
        val createdAt: OffsetDateTime,
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
        val notes: String?,
    )

    data class PatchFinancialRecordRequest(
        val status: String? = null,
        val notes: String? = null,
        val recordNumber: String? = null,
        val dueDate: LocalDate? = null,
    )

    data class RecordPaymentRequest(
        @field:NotNull val treatmentPlanId: Long,
        @field:Min(1) val amountMinor: Long,
        val currency: String = "UZS",
        @field:NotNull val method: TreatmentPlanPayment.PaymentMethod,
        val memo: String?,
        val financialRecordId: Long? = null,
        /** When set, payment applies to Finance → By appointment for this visit only (see ClinicFinanceLedgerService). */
        val linkedAppointmentId: Long? = null,
    )

    data class PaymentHistoryDto(
        val id: Long,
        val treatmentPlanId: Long,
        val patientId: Long?,
        val patientName: String?,
        val doctorProfileId: Long?,
        val doctorName: String?,
        val treatmentPlanTitle: String?,
        val amountMinor: Long,
        val currency: String,
        val method: String,
        val memo: String?,
        val financialRecordId: Long?,
        val linkedAppointmentId: Long?,
        val recordedAt: OffsetDateTime,
    )

    data class InstallmentItemSpecDto(
        @field:NotNull val dueDate: LocalDate,
        @field:Min(1) val amountMinor: Long,
    )

    data class CreateInstallmentRequest(
        @field:NotNull val treatmentPlanId: Long,
        @field:Min(1) val totalAmountMinor: Long,
        val currency: String = "UZS",
        @field:Min(2) val numInstallments: Int = 2,
        val frequency: String = "MONTHLY",
        @field:NotNull val startDate: LocalDate,
        val notes: String?,
        val scheduleItems: List<InstallmentItemSpecDto>? = null,
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
        val items: List<InstallmentItemDto>,
    )

    data class InstallmentItemDto(
        val id: Long,
        val sequenceNumber: Int,
        val dueDate: LocalDate,
        val amountMinor: Long,
        val currency: String,
        val status: String,
        val paidAt: OffsetDateTime?,
        val notes: String?,
    )

    /** Installment row for the clinic finance "Installments" tab (includes patient/plan context). */
    data class InstallmentItemListDto(
        val id: Long,
        val sequenceNumber: Int,
        val dueDate: LocalDate,
        val amountMinor: Long,
        val currency: String,
        val status: String,
        val paidAt: OffsetDateTime?,
        val notes: String?,
        val installmentPlanId: Long,
        val treatmentPlanId: Long,
        val treatmentPlanTitle: String?,
        val patientId: Long,
        val patientName: String,
    )

    data class PatchInstallmentItemRequest(
        val status: String? = null,
        val dueDate: LocalDate? = null,
        val notes: String? = null,
    )

    data class MarkInstallmentPaidRequest(
        @field:NotNull val method: TreatmentPlanPayment.PaymentMethod,
        val memo: String? = null,
    )

    data class FinanceAuditLogDto(
        val id: Long,
        val actionType: String,
        val entityType: String,
        val entityId: Long?,
        val details: String?,
        val createdAt: OffsetDateTime,
    )

    // --- Endpoints ---

    @GetMapping("/dashboard")
    @Transactional(readOnly = true)
    fun getDashboard(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
    ): FinanceDashboardDto {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        val patientFilter = financeAccess.financeReadPatientIdFilter(principal, clinicId)

        if (patientFilter != null && patientFilter.isEmpty()) {
            return FinanceDashboardDto(
                totalRevenueMinor = 0,
                outstandingMinor = 0,
                overdueCount = 0,
                collectionRate = 0.0,
                currency = "UZS",
                doctorEarningsTop = emptyList(),
            )
        }

        // Treatment-plan KPIs must include PLANS FULLY PAID (remaining == 0), otherwise totals and
        // collection rates are wildly wrong versus doctor earnings / real payments.
        val plansRaw = treatmentPlans.findByClinic_IdOrderByUpdatedAtDescIdDesc(clinicId)
        val plans = plansRaw
            .filter { it.status != TreatmentPlan.Status.CANCELLED }
            .filter { patientFilter == null || (it.patient.id != null && it.patient.id in patientFilter) }
        var totalRevenue = 0L
        var totalExpected = 0L
        var outstandingTreatmentPlans = 0L
        for (plan in plans) {
            val live = financeService.planLiveTotals(plan.id)
            totalRevenue += live.paidMinor
            totalExpected += live.estimatedMinor
            outstandingTreatmentPlans += live.remainingMinor
        }
        val collectionRate =
            when {
                totalExpected > 0L ->
                    (totalRevenue.toDouble() / totalExpected.toDouble()).coerceIn(0.0, 1.0)
                totalRevenue > 0L -> 1.0
                else -> 0.0
            }

        // Also surface invoice-style debt (optional visibility for clinics using formal records).
        val outstandingInvoiceAll = financialRecords.findByClinicIdAndStatusInAndRemainingMinorGreaterThanZero(
            clinicId,
            listOf(FinancialRecord.Status.ISSUED, FinancialRecord.Status.PARTIALLY_PAID, FinancialRecord.Status.OVERDUE),
        )
        val outstandingInvoice = patientFilter?.let { pids -> outstandingInvoiceAll.filter { it.patient.id in pids } } ?: outstandingInvoiceAll
        val outstandingInvoiceMinor = outstandingInvoice.sumOf { it.remainingMinor.coerceAtLeast(0L) }

        val outstandingMinor = outstandingTreatmentPlans + outstandingInvoiceMinor

        val financeInvoiceOverdue = outstandingInvoice.count { it.status == FinancialRecord.Status.OVERDUE }
        val todayUtc = LocalDate.now(ZoneOffset.UTC)
        val installmentOverdue = installmentItems.findOverdueByClinic(clinicId, todayUtc)
            .filter { ii ->
                patientFilter == null ||
                    (
                        runCatching { ii.installmentPlan.treatmentPlan.patient.id }
                            .getOrNull()
                            ?.let { pid -> pid in patientFilter }
                            ?: false
                        )
            }
        val overdueCount = financeInvoiceOverdue + installmentOverdue.size

        val monthStart = YearMonth.now().atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val monthEnd = YearMonth.now().plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val earnings = ledgerService.doctorEarnings(clinicId, monthStart, monthEnd, patientFilter)
            .take(10)
            .map {
                DoctorEarningRowDto(
                    doctorProfileId = it.doctorProfileId,
                    visitCount = it.visitCount,
                    grossMinor = it.grossMinor,
                    collectedMinor = it.collectedMinor,
                    outstandingMinor = it.outstandingMinor,
                )
            }

        return FinanceDashboardDto(
            totalRevenueMinor = totalRevenue,
            outstandingMinor = outstandingMinor,
            overdueCount = overdueCount,
            collectionRate = collectionRate,
            currency = "UZS",
            doctorEarningsTop = earnings,
        )
    }

    @GetMapping("/appointment-ledger")
    @Transactional(readOnly = true)
    fun appointmentLedger(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): Map<String, Any> {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        val patientFilter = financeAccess.financeReadPatientIdFilter(principal, clinicId)
        val pageable = PageRequest.of(page, size)

        val apptPage = when {
            patientFilter == null ->
                linesRepo.findDistinctLinkedAppointmentIdsForClinic(clinicId, pageable)
            patientFilter.isEmpty() ->
                org.springframework.data.domain.PageImpl(emptyList<Long>(), pageable, 0)
            else ->
                linesRepo.findDistinctLinkedAppointmentIdsForClinicAndPatients(
                    clinicId,
                    patientFilter.toList(),
                    pageable,
                )
        }

        val rows = mutableListOf<AppointmentLedgerRowDto>()
        for (apptId in apptPage.content) {
            val row = buildAppointmentLedgerRow(apptId, patientFilter) ?: continue
            rows += row
        }

        return mapOf(
            "content" to rows,
            "totalElements" to apptPage.totalElements,
            "totalPages" to apptPage.totalPages,
            "number" to apptPage.number,
        )
    }

    @GetMapping("/doctor-earnings")
    @Transactional(readOnly = true)
    fun doctorEarnings(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @RequestParam from: String?,
        @RequestParam to: String?,
    ): List<DoctorEarningRowDto> {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        val patientFilter = financeAccess.financeReadPatientIdFilter(principal, clinicId)
        val fromInst = from?.let { Instant.parse(it) }
            ?: YearMonth.now().atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val toInst = to?.let { Instant.parse(it) }
            ?: YearMonth.now().plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        return ledgerService.doctorEarnings(clinicId, fromInst, toInst, patientFilter).map {
            DoctorEarningRowDto(
                doctorProfileId = it.doctorProfileId,
                visitCount = it.visitCount,
                grossMinor = it.grossMinor,
                collectedMinor = it.collectedMinor,
                outstandingMinor = it.outstandingMinor,
            )
        }
    }

    @GetMapping("/audit")
    @Transactional(readOnly = true)
    fun listAudit(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @RequestParam(required = false) recordId: Long?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): Map<String, Any> {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        financeAccess.assertCanViewFinance(principal, clinicId)
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val result = if (recordId != null) {
            auditLogs.findByClinic_IdAndEntityTypeAndEntityIdOrderByCreatedAtDesc(
                clinicId,
                "FINANCIAL_RECORD",
                recordId,
                pageable,
            )
        } else {
            auditLogs.findByClinic_IdOrderByCreatedAtDesc(clinicId, pageable)
        }
        return mapOf(
            "content" to result.content.map {
                FinanceAuditLogDto(
                    id = it.id,
                    actionType = it.actionType,
                    entityType = it.entityType,
                    entityId = it.entityId,
                    details = it.details,
                    createdAt = it.createdAt,
                )
            },
            "totalElements" to result.totalElements,
            "totalPages" to result.totalPages,
            "number" to result.number,
        )
    }

    @GetMapping("/records")
    @Transactional(readOnly = true)
    fun listRecords(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): Map<String, Any> {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        val patientFilter = financeAccess.financeReadPatientIdFilter(principal, clinicId)
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))

        val result = when {
            patientFilter == null -> {
                if (status != null) {
                    val s = FinancialRecord.Status.valueOf(status.uppercase())
                    financialRecords.findByClinic_IdAndStatus(clinicId, s, pageable)
                } else {
                    financialRecords.findByClinic_IdOrderByCreatedAtDesc(clinicId, pageable)
                }
            }
            patientFilter.isEmpty() ->
                org.springframework.data.domain.PageImpl(emptyList(), pageable, 0)
            else -> {
                val pids = patientFilter.toList()
                if (status != null) {
                    val s = FinancialRecord.Status.valueOf(status.uppercase())
                    financialRecords.findByClinic_IdAndStatusAndPatient_IdInOrderByCreatedAtDesc(clinicId, s, pids, pageable)
                } else {
                    financialRecords.findByClinic_IdAndPatient_IdInOrderByCreatedAtDesc(clinicId, pids, pageable)
                }
            }
        }

        return mapOf(
            "content" to result.content.map { toRecordDto(it) },
            "totalElements" to result.totalElements,
            "totalPages" to result.totalPages,
            "number" to result.number,
        )
    }

    @PostMapping("/records")
    @Transactional
    fun createRecord(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @RequestBody @Valid req: CreateFinancialRecordRequest,
    ): FinancialRecordDto {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        financeAccess.assertCanManagePatientFinance(principal, clinicId, req.patientId)

        if (req.treatmentPlanId != null) {
            val plan = treatmentPlans.findById(req.treatmentPlanId).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Treatment plan not found")
            }
            if (plan.clinic.id != clinicId) throw ResponseStatusException(HttpStatus.NOT_FOUND)
            financeAccess.assertCanManagePatientFinance(principal, clinicId, plan.patient.id!!)
            val uid = clinicAccess.resolveBookingActorUserId(principal)
            val user = users.findById(uid).orElse(null)
            val record = financeService.generateFinancialRecord(
                req.treatmentPlanId,
                FinancialRecord.RecordType.valueOf(req.recordType.uppercase()),
                user,
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
                createdByUser = user,
            ),
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
        @PathVariable recordId: Long,
    ): FinancialRecordDto {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        financeAccess.assertCanViewFinance(principal, clinicId)
        val record = financialRecords.findById(recordId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found")
        }
        if (record.clinic.id != clinicId) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        financeAccess.assertCanManagePatientFinance(principal, clinicId, record.patient.id!!)
        return toRecordDto(record)
    }

    @PatchMapping("/records/{recordId}")
    @Transactional
    fun patchRecord(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @PathVariable recordId: Long,
        @RequestBody body: PatchFinancialRecordRequest,
    ): FinancialRecordDto {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        val record = financialRecords.findById(recordId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found")
        }
        if (record.clinic.id != clinicId) throw ResponseStatusException(HttpStatus.NOT_FOUND)

        val voiding = body.status?.let { FinancialRecord.Status.valueOf(it.uppercase()) == FinancialRecord.Status.VOIDED } == true
        if (voiding) {
            financeAccess.assertCanVoidRecords(principal, clinicId)
            if (record.paidMinor > 0) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot void a record that has recorded payments")
            }
        } else {
            financeAccess.assertCanManagePatientFinance(principal, clinicId, record.patient.id!!)
        }

        body.status?.let {
            val newStatus = FinancialRecord.Status.valueOf(it.uppercase())
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
        @RequestBody @Valid req: RecordPaymentRequest,
    ): PaymentHistoryDto {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        val plan = treatmentPlans.findById(req.treatmentPlanId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Treatment plan not found")
        }
        if (plan.clinic.id != clinicId) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        financeAccess.assertCanManagePatientFinance(principal, clinicId, plan.patient.id!!)

        val linkedAppt =
            req.linkedAppointmentId?.let { aid ->
                val appt = appts.findById(aid).orElseThrow {
                    ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $aid")
                }
                if (plan.patient.id != appt.patient?.id) {
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Appointment patient does not match treatment plan patient",
                    )
                }
                val touchesPlan =
                    linesRepo.findByLinkedAppointment_Id(aid).any { it.plan.id == plan.id }
                if (!touchesPlan) {
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "This appointment has no charges linked to the selected treatment plan",
                    )
                }
                appt
            }

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
            financialRecord = fr,
            linkedAppointment = linkedAppt,
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
        @RequestParam(defaultValue = "50") size: Int,
    ): List<PaymentHistoryDto> {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        val patientFilter = financeAccess.financeReadPatientIdFilter(principal, clinicId)
        val pageable = PageRequest.of(page, size)
        val statuses = listOf(
            com.shifa.domain.TreatmentPlan.Status.ACTIVE,
            com.shifa.domain.TreatmentPlan.Status.IN_PROGRESS,
            com.shifa.domain.TreatmentPlan.Status.COMPLETED,
        )
        val payments = when {
            patientFilter == null ->
                treatmentPlanPayments.findByClinicAndPlanStatuses(clinicId, statuses, pageable)
            patientFilter.isEmpty() -> emptyList()
            else ->
                treatmentPlanPayments.findByClinicAndPlanStatusesAndPatientIds(
                    clinicId,
                    statuses,
                    patientFilter.toList(),
                    pageable,
                )
        }
        return payments.map { toPaymentDto(it) }
    }

    /**
     * Lists installment payment rows for the clinic finance tab.
     *
     * [filter]: `all` (default), `pending` (upcoming / not yet due), `overdue`, `paid`.
     * Previously the UI only called `/overdue`, so newly created plans with future
     * due dates never appeared.
     */
    @GetMapping("/installment-items")
    @Transactional(readOnly = true)
    fun listInstallmentItems(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @RequestParam(defaultValue = "all") filter: String,
    ): List<InstallmentItemListDto> {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        val patientFilter = financeAccess.financeReadPatientIdFilter(principal, clinicId)
        val today = java.time.LocalDate.now()
        var items = installmentItems.findActiveByClinic(clinicId)
        if (patientFilter != null) {
            items = items.filter { it.installmentPlan.treatmentPlan.patient.id in patientFilter }
        }
        items = when (filter.lowercase()) {
            "pending" -> items.filter { it.status == InstallmentItem.Status.PENDING }
            "overdue" -> items.filter {
                it.status == InstallmentItem.Status.OVERDUE ||
                    (it.status == InstallmentItem.Status.PENDING && it.dueDate.isBefore(today))
            }
            "paid" -> items.filter { it.status == InstallmentItem.Status.PAID }
            else -> items
        }
        return items.map { toInstallmentItemListDto(it) }
    }

    @GetMapping("/overdue")
    @Transactional(readOnly = true)
    fun getOverdue(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
    ): Map<String, Any> {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        val patientFilter = financeAccess.financeReadPatientIdFilter(principal, clinicId)

        val overdueRecordsAll = financialRecords.findByClinicIdAndStatusInAndRemainingMinorGreaterThanZero(
            clinicId,
            listOf(FinancialRecord.Status.ISSUED, FinancialRecord.Status.PARTIALLY_PAID, FinancialRecord.Status.OVERDUE),
        )
            .filter { it.status == FinancialRecord.Status.OVERDUE }
        val overdueRecords = patientFilter?.let { pids -> overdueRecordsAll.filter { it.patient.id in pids } } ?: overdueRecordsAll

        val overdueInstallmentsAll = installmentService.getOverdueItems(clinicId)
        val overdueInstallments = patientFilter?.let { pids ->
            overdueInstallmentsAll.filter {
                val pid = it.installmentPlan.treatmentPlan.patient.id
                pid != null && pid in pids
            }
        } ?: overdueInstallmentsAll

        return mapOf(
            "overdueRecords" to overdueRecords.map { toRecordDto(it) },
            "overdueInstallments" to overdueInstallments.map { toInstallmentItemDto(it) },
        )
    }

    @PostMapping("/installment-plans")
    @Transactional
    fun createInstallmentPlan(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @RequestBody @Valid req: CreateInstallmentRequest,
    ): InstallmentPlanDto {
        val log = org.slf4j.LoggerFactory.getLogger(ClinicFinanceController::class.java)
        log.info(
            "installment-plans POST clinicId={} planId={} total={} rows={} freq={}",
            clinicId,
            req.treatmentPlanId,
            req.totalAmountMinor,
            req.scheduleItems?.size,
            req.frequency,
        )

        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        val tp = treatmentPlans.findById(req.treatmentPlanId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Treatment plan not found")
        }
        if (tp.clinic.id != clinicId) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val patientId = tp.patient.id
            ?: throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Treatment plan ${req.treatmentPlanId} has no patient id",
            )
        financeAccess.assertCanManagePatientFinance(principal, clinicId, patientId)

        val uid = clinicAccess.resolveBookingActorUserId(principal)
        val user = users.findById(uid).orElse(null)

        val sched = req.scheduleItems?.map {
            InstallmentService.InstallmentScheduleItem(it.dueDate, it.amountMinor)
        }
        if (sched != null && sched.size < 2) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "At least 2 schedule rows required")
        }

        val frequency = try {
            InstallmentPlan.Frequency.valueOf(req.frequency.uppercase())
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Unknown frequency '${req.frequency}'. Allowed: WEEKLY, BIWEEKLY, MONTHLY, CUSTOM",
            )
        }

        val plan = try {
            installmentService.createInstallmentPlan(
                treatmentPlanId = req.treatmentPlanId,
                request = InstallmentService.CreateInstallmentRequest(
                    totalAmountMinor = req.totalAmountMinor,
                    currency = req.currency,
                    numInstallments = if (sched != null) sched.size else req.numInstallments,
                    frequency = frequency,
                    startDate = req.startDate,
                    notes = req.notes?.trim(),
                    scheduleItems = sched,
                ),
                createdByUser = user,
            )
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        } catch (e: Exception) {
            log.error(
                "[INST-STEP service.create] clinicId={} planId={} -> {}",
                clinicId,
                req.treatmentPlanId,
                e.javaClass.simpleName,
                e,
            )
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Step:service.create -> ${e.javaClass.simpleName}: ${e.message?.take(200)}",
            )
        }

        // Diagnostic step labels so the client snackbar tells us exactly where
        // a post-service failure occurs (e.g. audit-log save, notification
        // save, lazy-load of attendingDoctor, or Spring's final commit).
        fun stepThrow(step: String, e: Exception): Nothing {
            log.error("[INST-STEP $step] planId={} -> {}", plan.id, e.javaClass.simpleName, e)
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Step:$step -> ${e.javaClass.simpleName}: ${e.message?.take(200)}",
            )
        }

        val clinic = try {
            clinics.findById(clinicId).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Clinic $clinicId not found")
            }
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            stepThrow("clinics.findById", e)
        }

        try {
            user?.let {
                auditService.log(clinic, it, "CREATE", "INSTALLMENT_PLAN", plan.id)
            }
        } catch (e: Exception) {
            log.warn("Audit log failed for installment plan {}: {}", plan.id, e.message)
        }

        val items = try {
            installmentItems.findByInstallmentPlan_IdOrderBySequenceNumberAsc(plan.id)
        } catch (e: Exception) {
            stepThrow("items.list (auto-flush)", e)
        }

        // Never let a notification / FCM failure roll back installment creation.
        // The installment plan + items are the actual deliverable here; the
        // patient notification is best-effort.
        try {
            val patient = tp.patient
            // Patient-only notification — `doctor` stays null so the row does not
            // leak into the attending doctor's notifications feed.
            val notif = Notification(
                patient = patient,
                doctor = null,
                title = "Payment schedule",
                message = "Your clinic created a payment plan with ${items.size} installments. Please review due dates in the app.",
                type = Notification.Type.INSTALLMENT_SCHEDULE_CREATED,
                treatmentPlanId = tp.id,
            )
            val savedNotif = notifications.save(notif)
            patient.fcmToken?.let {
                fcmService.sendPatientNotification(
                    it,
                    savedNotif,
                    mapOf("route" to "/bookings/treatment-plan/${tp.id}"),
                )
            }
        } catch (e: Exception) {
            log.warn(
                "Installment plan {} saved but patient notification failed: {} {}",
                plan.id,
                e.javaClass.simpleName,
                e.message,
            )
        }

        try {
            val totalScheduled = items.sumOf { it.amountMinor }
            val firstDue = items.minOfOrNull { it.dueDate }
            financeService.ensureInstallmentInvoice(
                treatmentPlan = tp,
                installmentPlanId = plan.id,
                totalScheduledMinor = totalScheduled,
                currency = plan.currency,
                firstDueDate = firstDue,
                createdByUser = user,
            )
        } catch (e: Exception) {
            log.warn(
                "Installment invoice not created planId={} treatmentPlan={}: {}",
                plan.id,
                tp.id,
                e.message,
            )
        }

        val dto = try {
            toInstallmentPlanDto(plan, items)
        } catch (e: Exception) {
            stepThrow("toDto", e)
        }
        return dto
    }

    @GetMapping("/installment-plans/by-treatment-plan/{treatmentPlanId}")
    @Transactional(readOnly = true)
    fun listInstallmentPlansForTreatmentPlan(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @PathVariable treatmentPlanId: Long,
    ): List<InstallmentPlanDto> {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        financeAccess.assertCanViewFinance(principal, clinicId)
        val tp = treatmentPlans.findById(treatmentPlanId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Treatment plan not found")
        }
        if (tp.clinic.id != clinicId) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        financeAccess.assertCanManagePatientFinance(principal, clinicId, tp.patient.id!!)
        return installmentPlans.findByTreatmentPlan_IdOrderByCreatedAtDesc(treatmentPlanId).map { p ->
            val items = installmentItems.findByInstallmentPlan_IdOrderBySequenceNumberAsc(p.id)
            toInstallmentPlanDto(p, items)
        }
    }

    @GetMapping("/installment-plans/{planId}")
    @Transactional(readOnly = true)
    fun getInstallmentPlan(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @PathVariable planId: Long,
    ): InstallmentPlanDto {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        financeAccess.assertCanViewFinance(principal, clinicId)

        val plan = installmentPlans.findById(planId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Installment plan not found")
        }
        if (plan.treatmentPlan.clinic.id != clinicId) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        financeAccess.assertCanManagePatientFinance(principal, clinicId, plan.treatmentPlan.patient.id!!)
        val items = installmentItems.findByInstallmentPlan_IdOrderBySequenceNumberAsc(plan.id)
        return toInstallmentPlanDto(plan, items)
    }

    @PostMapping("/installment-items/{itemId}/mark-paid")
    @Transactional
    fun markInstallmentItemPaid(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @PathVariable itemId: Long,
        @RequestBody @Valid body: MarkInstallmentPaidRequest,
    ): InstallmentItemDto {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        val item = installmentItems.findById(itemId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Installment item not found")
        }
        val tp = item.installmentPlan.treatmentPlan
        if (tp.clinic.id != clinicId) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        financeAccess.assertCanManagePatientFinance(principal, clinicId, tp.patient.id!!)
        if (item.status == InstallmentItem.Status.PAID) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Already paid")
        }
        if (item.status == InstallmentItem.Status.CANCELLED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot pay a cancelled installment")
        }

        val uid = clinicAccess.resolveBookingActorUserId(principal)
        val user = users.findById(uid).orElse(null)
        val log = org.slf4j.LoggerFactory.getLogger(ClinicFinanceController::class.java)

        fun markStep(step: String, e: Exception): Nothing {
            log.error("[MARK-PAID-STEP $step] itemId={} -> {}", itemId, e.javaClass.simpleName, e)
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Step:$step -> ${e.javaClass.simpleName}: ${e.message?.take(200)}",
            )
        }

        val payment = try {
            financeService.recordPaymentAndSync(
                planId = tp.id,
                amountMinor = item.amountMinor,
                currency = item.currency,
                method = body.method,
                memo = body.memo?.trim(),
                recordedByUser = user,
                financialRecord = null,
            )
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            markStep("recordPaymentAndSync", e)
        }

        val updated = try {
            installmentService.markInstallmentPaid(itemId, payment)
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            markStep("markInstallmentPaid", e)
        }

        val clinic = clinics.findById(clinicId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Clinic $clinicId not found")
        }
        user?.let { auditService.log(clinic, it, "INSTALLMENT_PAID", "INSTALLMENT_ITEM", itemId) }
        return toInstallmentItemDto(updated)
    }

    @PatchMapping("/installment-items/{itemId}")
    @Transactional
    fun patchInstallmentItem(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @PathVariable itemId: Long,
        @RequestBody body: PatchInstallmentItemRequest,
    ): InstallmentItemDto {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        val item = installmentItems.findById(itemId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Installment item not found")
        }
        val tp = item.installmentPlan.treatmentPlan
        if (tp.clinic.id != clinicId) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        financeAccess.assertCanManagePatientFinance(principal, clinicId, tp.patient.id!!)
        if (item.status == InstallmentItem.Status.PAID) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot modify a paid installment")
        }
        body.status?.let {
            val st = InstallmentItem.Status.valueOf(it.uppercase())
            if (st == InstallmentItem.Status.PAID) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Use mark-paid endpoint")
            }
            item.status = st
        }
        body.dueDate?.let { item.dueDate = it }
        body.notes?.let { item.notes = it.trim() }
        installmentItems.save(item)
        return toInstallmentItemDto(item)
    }

    @PostMapping("/installment-items/{itemId}/notify")
    @Transactional
    fun notifyInstallmentItem(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @PathVariable itemId: Long,
    ): Map<String, String> {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        val item = installmentItems.findById(itemId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Installment item not found")
        }
        val tp = item.installmentPlan.treatmentPlan
        if (tp.clinic.id != clinicId) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        financeAccess.assertCanManagePatientFinance(principal, clinicId, tp.patient.id!!)
        val patient = tp.patient
        val msg =
            "Reminder: installment #${item.sequenceNumber} of ${item.amountMinor / 100.0} ${item.currency} is due ${item.dueDate}."
        val notif = Notification(
            patient = patient,
            doctor = null,
            title = "Installment reminder",
            message = msg,
            type = Notification.Type.TREATMENT_PLAN_PAYMENT_REMINDER,
            treatmentPlanId = tp.id,
            installmentItemId = item.id,
        )
        val saved = notifications.save(notif)
        patient.fcmToken?.let {
            fcmService.sendPatientNotification(
                it,
                saved,
                mapOf("route" to "/bookings/treatment-plan/${tp.id}", "installmentItemId" to item.id.toString()),
            )
        }
        return mapOf("status" to "sent")
    }

    // --- Mappers ---

    private fun buildAppointmentLedgerRow(
        appointmentId: Long,
        patientFilter: Set<Long>?,
    ): AppointmentLedgerRowDto? {
        val appt = appts.findByIdWithDoctorAndPatient(appointmentId).orElse(null) ?: return null
        if (appt.status == com.shifa.domain.Appointment.Status.CANCELLED) return null
        val patientId = appt.patient.id ?: return null
        if (patientFilter != null && patientId !in patientFilter) return null

        val lines = linesRepo.findByLinkedAppointment_Id(appointmentId)
        if (lines.isEmpty()) return null
        val plan = lines.first().plan
        val visitTotal = lines.sumOf { ledgerService.lineTotal(it) }
        val planEstimated = plan.estimatedTotalMinor.coerceAtLeast(1L)
        val payments = treatmentPlanPayments.findByPlan_IdOrderByRecordedAtAsc(plan.id)
        val visitPaid = ledgerService.appointmentAttributedPaidMinorFromPayments(
            appt.id,
            visitTotal,
            planEstimated,
            payments,
        )
        val rowStatus = ledgerService.planSimplePaymentStatus(visitTotal, visitPaid)
        val services = lines.map {
            AppointmentLedgerRowDto.ServiceLineDto(it.title, ledgerService.lineTotal(it))
        }

        return AppointmentLedgerRowDto(
            appointmentId = appt.id,
            startAt = appt.startAt.toString(),
            patientId = patientId,
            patientName = appt.patient.fullName ?: "",
            doctorProfileId = appt.doctor.id!!,
            doctorName = "${appt.doctor.firstName} ${appt.doctor.lastName}",
            treatmentPlanId = plan.id,
            services = services,
            visitTotalMinor = visitTotal,
            currency = lines.first().currency,
            planPaymentStatus = rowStatus,
            planSimplePaymentStatus = rowStatus,
        )
    }

    private fun recordUiPaymentStatus(r: FinancialRecord): String {
        return ledgerService.planSimplePaymentStatus(r.totalMinor, r.paidMinor)
    }

    private fun toRecordDto(r: FinancialRecord) = FinancialRecordDto(
        id = r.id,
        clinicId = r.clinic.id!!,
        patientId = r.patient.id!!,
        treatmentPlanId = r.treatmentPlan?.id,
        recordType = r.recordType.name,
        recordNumber = r.recordNumber,
        status = r.status.name,
        uiPaymentStatus = recordUiPaymentStatus(r),
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
        createdAt = r.createdAt,
    )

    private fun toPaymentDto(p: TreatmentPlanPayment) = PaymentHistoryDto(
        id = p.id,
        treatmentPlanId = p.plan.id,
        patientId = runCatching { p.plan.patient.id }.getOrNull(),
        patientName = runCatching {
            p.plan.patient.fullName.trim().ifEmpty {
                val pid = p.plan.patient.id
                if (pid != null) "Patient #$pid" else null
            }
        }.getOrNull(),
        doctorProfileId = runCatching { p.plan.attendingDoctor?.id }.getOrNull(),
        doctorName = runCatching {
            val d = p.plan.attendingDoctor
            when {
                d == null -> null
                else -> {
                    val n = "${d.firstName ?: ""} ${d.lastName ?: ""}".trim()
                    n.ifEmpty { null }
                }
            }
        }.getOrNull(),
        treatmentPlanTitle = runCatching { p.plan.title?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull(),
        amountMinor = p.amountMinor,
        currency = p.currency,
        method = p.method.name,
        memo = p.memo,
        financialRecordId = p.financialRecord?.id,
        linkedAppointmentId = runCatching { p.linkedAppointment?.id }.getOrNull(),
        recordedAt = p.recordedAt,
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
        items = items.map { toInstallmentItemDto(it) },
    )

    private fun toInstallmentItemDto(item: InstallmentItem) = InstallmentItemDto(
        id = item.id,
        sequenceNumber = item.sequenceNumber,
        dueDate = item.dueDate,
        amountMinor = item.amountMinor,
        currency = item.currency,
        status = item.status.name,
        paidAt = item.paidAt,
        notes = item.notes,
    )

    private fun toInstallmentItemListDto(item: InstallmentItem): InstallmentItemListDto {
        val tp = item.installmentPlan.treatmentPlan
        val patientName = tp.patient.fullName.trim().ifEmpty { "Patient #${tp.patient.id}" }
        return InstallmentItemListDto(
            id = item.id,
            sequenceNumber = item.sequenceNumber,
            dueDate = item.dueDate,
            amountMinor = item.amountMinor,
            currency = item.currency,
            status = item.status.name,
            paidAt = item.paidAt,
            notes = item.notes,
            installmentPlanId = item.installmentPlan.id,
            treatmentPlanId = tp.id,
            treatmentPlanTitle = tp.title,
            patientId = tp.patient.id!!,
            patientName = patientName,
        )
    }
}
