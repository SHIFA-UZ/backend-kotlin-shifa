package com.shifa.web

import com.shifa.domain.Notification
import com.shifa.domain.TreatmentPlan
import com.shifa.domain.TreatmentPlanLine
import com.shifa.domain.TreatmentPlanPayment
import com.shifa.domain.TreatmentPlanCatalogItem
import com.shifa.repo.*
import com.shifa.service.ClinicAccessService
import com.shifa.service.FcmService
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime

@RestController
@RequestMapping("/api/treatment-plans")
class TreatmentPlanController(
    private val clinicAccess: ClinicAccessService,
    private val clinics: ClinicRepository,
    private val patients: PatientProfileRepository,
    private val doctors: DoctorProfileRepository,
    private val catalogRepo: TreatmentPlanCatalogItemRepository,
    private val plans: TreatmentPlanRepository,
    private val linesRepo: TreatmentPlanLineRepository,
    private val paymentsRepo: TreatmentPlanPaymentRepository,
    private val users: UserRepository,
    private val notifications: NotificationRepository,
    private val fcmService: FcmService,
) {

    data class CatalogItemDto(
        val id: Long,
        val clinicId: Long,
        val code: String?,
        val title: String,
        val defaultPriceMinor: Long,
        val currency: String,
        val active: Boolean,
        val sortOrder: Int
    )

    data class UpsertCatalogItemRequest(
        @field:NotNull val clinicId: Long,
        val code: String?,
        @field:NotBlank val title: String,
        @field:Min(0) val defaultPriceMinor: Long,
        val currency: String = "UZS",
        val active: Boolean = true,
        val sortOrder: Int = 0
    )

    data class TreatmentPlanSummaryDto(
        val id: Long,
        val clinicId: Long,
        val patientId: Long?,
        val status: String,
        val notes: String?,
        val paymentReminderDays: Int?,
        val totalMinor: Long,
        val paidMinor: Long,
        val owedMinor: Long,
        val currency: String
    )

    data class LineReq(
        val catalogItemId: Long?,
        @field:NotBlank val title: String,
        @field:Min(1) val quantity: Int,
        @field:Min(0) val unitPriceMinor: Long,
        @field:Min(0) val discountMinor: Long = 0,
        val currency: String = "UZS",
        val sortOrder: Int = 0
    )

    data class CreatePlanRequest(
        @field:NotNull val clinicId: Long,
        @field:NotNull val patientId: Long,
        val attendingDoctorId: Long?,
        val notes: String?,
        val paymentReminderDays: Int?
    )

    data class PatchPlanStatusRequest(val status: TreatmentPlan.Status)

    data class PaymentReq(
        @field:Min(1) val amountMinor: Long,
        val currency: String = "UZS",
        @field:NotNull val method: TreatmentPlanPayment.PaymentMethod,
        val memo: String?
    )

    private fun totals(planId: Long): Triple<Long, Long, String> {
        val lineRows = linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(planId)
        val currency = lineRows.firstOrNull()?.currency ?: "UZS"
        val total = lineRows.sumOf { (it.unitPriceMinor * it.quantity - it.discountMinor).coerceAtLeast(0) }
        val paid = paymentsRepo.findByPlan_IdOrderByRecordedAtAsc(planId).sumOf { it.amountMinor }
        return Triple(total, paid, currency)
    }

    private fun toSummary(plan: TreatmentPlan): TreatmentPlanSummaryDto {
        val (total, paid, cur) = totals(plan.id)
        return TreatmentPlanSummaryDto(
            id = plan.id,
            clinicId = plan.clinic.id,
            patientId = plan.patient.id,
            status = plan.status.name,
            notes = plan.notes,
            paymentReminderDays = plan.paymentReminderDays,
            totalMinor = total,
            paidMinor = paid,
            owedMinor = (total - paid).coerceAtLeast(0),
            currency = cur
        )
    }

    private fun notifyPatient(plan: TreatmentPlan, type: Notification.Type, title: String, message: String) {
        val patient = plan.patient
        val pid = patient.id ?: return
        val n = Notification(
            patient = patient,
            doctor = plan.attendingDoctor,
            title = title,
            message = message,
            type = type,
            treatmentPlanId = plan.id
        )
        val saved = notifications.save(n)
        patient.fcmToken?.let {
            fcmService.sendPatientNotification(
                it,
                saved,
                mapOf("route" to "/bookings/treatment-plan/${plan.id}")
            )
        }
    }

    @GetMapping("/catalog-items")
    fun listCatalog(
        @AuthenticationPrincipal principal: Any,
        @RequestParam clinicId: Long
    ): List<CatalogItemDto> {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        return catalogRepo.findByClinic_IdAndActiveTrueOrderBySortOrderAscIdAsc(clinicId).map {
            CatalogItemDto(
                id = it.id,
                clinicId = it.clinic.id,
                code = it.code,
                title = it.title,
                defaultPriceMinor = it.defaultPriceMinor,
                currency = it.currency,
                active = it.active,
                sortOrder = it.sortOrder
            )
        }
    }

    @PostMapping("/catalog-items")
    @Transactional
    fun createCatalogItem(
        @AuthenticationPrincipal principal: Any,
        @RequestBody @Valid req: UpsertCatalogItemRequest
    ): CatalogItemDto {
        clinicAccess.assertPrincipalMayAccessClinic(principal, req.clinicId)
        val clinic = clinics.findById(req.clinicId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Clinic not found")
        }
        val saved = catalogRepo.save(
            TreatmentPlanCatalogItem(
                clinic = clinic,
                code = req.code?.trim()?.takeIf { it.isNotEmpty() },
                title = req.title.trim(),
                defaultPriceMinor = req.defaultPriceMinor,
                currency = req.currency,
                active = req.active,
                sortOrder = req.sortOrder
            )
        )
        return CatalogItemDto(
            id = saved.id,
            clinicId = saved.clinic.id,
            code = saved.code,
            title = saved.title,
            defaultPriceMinor = saved.defaultPriceMinor,
            currency = saved.currency,
            active = saved.active,
            sortOrder = saved.sortOrder
        )
    }

    @PostMapping
    @Transactional
    fun createPlan(
        @AuthenticationPrincipal principal: Any,
        @RequestBody @Valid req: CreatePlanRequest
    ): TreatmentPlanSummaryDto {
        clinicAccess.assertPrincipalMayAccessClinic(principal, req.clinicId)
        clinicAccess.assertPatientVisible(principal, req.patientId)
        val clinic = clinics.findById(req.clinicId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Clinic not found")
        }
        val patient = patients.findById(req.patientId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
        }
        val attending = req.attendingDoctorId?.let {
            doctors.findById(it).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found") }
                .also { d ->
                    clinicAccess.assertCanViewDoctorCalendar(principal, d.id!!)
                }
        }
        val uid = clinicAccess.resolveBookingActorUserId(principal)
        val user = users.findById(uid).orElse(null)
        val plan = plans.save(
            TreatmentPlan(
                clinic = clinic,
                patient = patient,
                attendingDoctor = attending,
                status = TreatmentPlan.Status.DRAFT,
                notes = req.notes?.trim(),
                paymentReminderDays = req.paymentReminderDays,
                createdByUser = user
            )
        )
        return toSummary(plan)
    }

    @GetMapping("/{planId}")
    fun getPlan(
        @AuthenticationPrincipal principal: Any,
        @PathVariable planId: Long
    ): TreatmentPlanSummaryDto {
        val plan = plans.findById(planId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found")
        }
        clinicAccess.assertPrincipalMayAccessClinic(principal, plan.clinic.id)
        clinicAccess.assertPatientVisible(principal, plan.patient.id!!)
        return toSummary(plan)
    }

    @PostMapping("/{planId}/lines")
    @Transactional
    fun replaceLines(
        @AuthenticationPrincipal principal: Any,
        @PathVariable planId: Long,
        @RequestBody body: List<LineReq>
    ): TreatmentPlanSummaryDto {
        val plan = plans.findById(planId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found")
        }
        clinicAccess.assertPrincipalMayAccessClinic(principal, plan.clinic.id)
        clinicAccess.assertPatientVisible(principal, plan.patient.id!!)
        linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(planId).forEach { linesRepo.delete(it) }
        for (row in body) {
            val catalog = row.catalogItemId?.let {
                catalogRepo.findById(it).orElseThrow { ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad catalog item") }
                    .also { c ->
                        if (c.clinic.id != plan.clinic.id) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Catalog mismatch")
                    }
            }
            linesRepo.save(
                TreatmentPlanLine(
                    plan = plan,
                    catalogItem = catalog,
                    title = row.title.trim(),
                    quantity = row.quantity,
                    unitPriceMinor = row.unitPriceMinor,
                    discountMinor = row.discountMinor,
                    currency = row.currency,
                    sortOrder = row.sortOrder
                )
            )
        }
        plan.updatedAt = OffsetDateTime.now()
        plans.save(plan)
        notifyPatient(plan, Notification.Type.TREATMENT_PLAN_UPDATED, "Treatment plan updated", "Your clinic updated your treatment plan. Please review payment details in the app.")
        return toSummary(plan)
    }

    @PatchMapping("/{planId}/status")
    @Transactional
    fun patchStatus(
        @AuthenticationPrincipal principal: Any,
        @PathVariable planId: Long,
        @RequestBody @Valid body: PatchPlanStatusRequest
    ): TreatmentPlanSummaryDto {
        val plan = plans.findById(planId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found")
        }
        clinicAccess.assertPrincipalMayAccessClinic(principal, plan.clinic.id)
        clinicAccess.assertPatientVisible(principal, plan.patient.id!!)
        plan.status = body.status
        plan.updatedAt = OffsetDateTime.now()
        plans.save(plan)
        notifyPatient(plan, Notification.Type.TREATMENT_PLAN_UPDATED, "Treatment plan status", "Your treatment plan status is now ${body.status.name}.")
        return toSummary(plan)
    }

    @PostMapping("/{planId}/payments")
    @Transactional
    fun recordPayment(
        @AuthenticationPrincipal principal: Any,
        @PathVariable planId: Long,
        @RequestBody @Valid body: PaymentReq
    ): TreatmentPlanSummaryDto {
        val plan = plans.findById(planId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found")
        }
        clinicAccess.assertPrincipalMayAccessClinic(principal, plan.clinic.id)
        clinicAccess.assertPatientVisible(principal, plan.patient.id!!)
        val uid = clinicAccess.resolveBookingActorUserId(principal)
        val user = users.findById(uid).orElse(null)
        paymentsRepo.save(
            TreatmentPlanPayment(
                plan = plan,
                amountMinor = body.amountMinor,
                currency = body.currency,
                method = body.method,
                memo = body.memo?.trim(),
                recordedByUser = user
            )
        )
        plan.updatedAt = OffsetDateTime.now()
        plans.save(plan)
        val owed = toSummary(plan).owedMinor
        if (owed > 0) {
            notifyPatient(
                plan,
                Notification.Type.TREATMENT_PLAN_PAYMENT_REMINDER,
                "Payment pending",
                "You have an outstanding balance on your treatment plan. Please arrange payment at the clinic."
            )
        }
        return toSummary(plan)
    }
}
