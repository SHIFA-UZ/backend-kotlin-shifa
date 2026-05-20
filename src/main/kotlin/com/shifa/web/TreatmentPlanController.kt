package com.shifa.web

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.domain.Notification
import com.shifa.domain.TreatmentPlan
import com.shifa.domain.TreatmentPlanLine
import com.shifa.domain.TreatmentPlanPayment
import com.shifa.domain.TreatmentPlanCatalogItem
import com.shifa.repo.*
import com.shifa.service.ClinicAccessService
import com.shifa.service.ClinicCatalogService
import com.shifa.service.FcmService
import com.shifa.service.TreatmentPlanFinanceService
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
    private val clinicCatalog: ClinicCatalogService,
    private val financeService: TreatmentPlanFinanceService,
    private val clinics: ClinicRepository,
    private val patients: PatientProfileRepository,
    private val doctors: DoctorProfileRepository,
    private val catalogRepo: TreatmentPlanCatalogItemRepository,
    private val plans: TreatmentPlanRepository,
    private val linesRepo: TreatmentPlanLineRepository,
    private val paymentsRepo: TreatmentPlanPaymentRepository,
    private val appts: AppointmentRepository,
    private val installmentPlans: InstallmentPlanRepository,
    private val users: UserRepository,
    private val notifications: NotificationRepository,
    private val fcmService: FcmService,
    private val objectMapper: ObjectMapper,
) {

    data class CatalogItemDto(
        val id: Long,
        val clinicId: Long,
        val code: String?,
        val title: String,
        val defaultPriceMinor: Long,
        val currency: String,
        val active: Boolean,
        val sortOrder: Int,
        val appliesToAllDoctors: Boolean,
        val assignedDoctorProfileIds: List<Long>,
    )

    data class UpsertCatalogItemRequest(
        @field:NotNull val clinicId: Long,
        val code: String?,
        @field:NotBlank val title: String,
        @field:Min(0) val defaultPriceMinor: Long,
        val currency: String = "UZS",
        val active: Boolean = true,
        val sortOrder: Int = 0,
        val appliesToAllDoctors: Boolean = true,
        val assignedDoctorProfileIds: List<Long> = emptyList(),
    )

    data class PatchCatalogItemRequest(
        val code: String? = null,
        val title: String? = null,
        @field:Min(0) val defaultPriceMinor: Long? = null,
        val currency: String? = null,
        val active: Boolean? = null,
        val sortOrder: Int? = null,
        val appliesToAllDoctors: Boolean? = null,
        val assignedDoctorProfileIds: List<Long>? = null,
    )

    data class TreatmentPlanSummaryDto(
        val id: Long,
        val clinicId: Long,
        val patientId: Long?,
        val patientName: String?,
        val attendingDoctorId: Long?,
        val attendingDoctorName: String?,
        val title: String?,
        val diagnosis: String?,
        val status: String,
        val notes: String?,
        val paymentReminderDays: Int?,
        val planKind: String,
        val symptoms: List<String>,
        val totalMinor: Long,
        val paidMinor: Long,
        val owedMinor: Long,
        val currency: String,
        val planPaymentStatus: String,
        val createdAt: String?,
        val updatedAt: String?,
    )

    data class AppointmentSummaryDto(
        val id: Long,
        val startAt: String,
        val endAt: String,
        val status: String,
        val doctorProfileId: Long,
        val doctorName: String,
    )

    data class LineDetailDto(
        val id: Long,
        val catalogItemId: Long?,
        val title: String,
        val quantity: Int,
        val unitPriceMinor: Long,
        val discountMinor: Long,
        val currency: String,
        val sortOrder: Int,
        val status: String,
        val linkedAppointment: AppointmentSummaryDto?,
        val lineTotalMinor: Long,
    )

    data class InstallmentSummaryDto(
        val installmentPlanId: Long,
        val status: String,
        val totalAmountMinor: Long,
        val currency: String,
        val numInstallments: Int,
    )

    data class TreatmentPlanDetailDto(
        val summary: TreatmentPlanSummaryDto,
        val lines: List<LineDetailDto>,
        val installmentPlans: List<InstallmentSummaryDto>,
    )

    data class LineReq(
        val catalogItemId: Long?,
        @field:NotBlank val title: String,
        @field:Min(1) val quantity: Int,
        @field:Min(0) val unitPriceMinor: Long,
        @field:Min(0) val discountMinor: Long = 0,
        val currency: String = "UZS",
        val sortOrder: Int = 0,
        val linkedAppointmentId: Long? = null,
        val status: TreatmentPlanLine.LineStatus? = null,
    )

    data class CreatePlanRequest(
        @field:NotNull val clinicId: Long,
        @field:NotNull val patientId: Long,
        val attendingDoctorId: Long?,
        val title: String?,
        val diagnosis: String?,
        val notes: String?,
        val paymentReminderDays: Int?,
        val symptoms: List<String>? = null,
        val planKind: TreatmentPlan.PlanKind? = null,
    )

    data class PatchPlanRequest(
        val title: String? = null,
        val diagnosis: String? = null,
        val notes: String? = null,
        val paymentReminderDays: Int? = null,
        val attendingDoctorId: Long? = null,
        val symptoms: List<String>? = null,
    )

    data class PatchPlanStatusRequest(val status: TreatmentPlan.Status)

    data class LinkAppointmentReq(val lineId: Long, val appointmentId: Long?)

    data class PaymentReq(
        @field:Min(1) val amountMinor: Long,
        val currency: String = "UZS",
        @field:NotNull val method: TreatmentPlanPayment.PaymentMethod,
        val memo: String?,
    )

    private fun lineAmt(line: TreatmentPlanLine): Long =
        (line.unitPriceMinor * line.quantity - line.discountMinor).coerceAtLeast(0)

    private fun parseSymptoms(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            objectMapper.readValue(json, object : TypeReference<List<String>>() {})
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun totals(planId: Long): Triple<Long, Long, String> {
        val lineRows = linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(planId)
        val currency = lineRows.firstOrNull()?.currency ?: "UZS"
        val total = lineRows.sumOf { lineAmt(it) }
        val paid = paymentsRepo.findByPlan_IdOrderByRecordedAtAsc(planId).sumOf { it.amountMinor }
        return Triple(total, paid, currency)
    }

    private fun planPaymentLabel(total: Long, paid: Long): String {
        if (total <= 0L) return "NONE"
        return when {
            paid <= 0L -> "UNPAID"
            paid >= total -> "PAID"
            else -> "PARTIAL"
        }
    }

    private fun toSummary(plan: TreatmentPlan): TreatmentPlanSummaryDto {
        val (total, paid, cur) = totals(plan.id)
        val patientName = plan.patient.fullName.trim().takeIf { it.isNotEmpty() }
        val doctor = plan.attendingDoctor
        val doctorName = doctor?.let { "${it.firstName} ${it.lastName}".trim() }
        return TreatmentPlanSummaryDto(
            id = plan.id,
            clinicId = plan.clinic.id!!,
            patientId = plan.patient.id,
            patientName = patientName,
            attendingDoctorId = doctor?.id,
            attendingDoctorName = doctorName,
            title = plan.title,
            diagnosis = plan.diagnosis,
            status = plan.status.name,
            notes = plan.notes,
            paymentReminderDays = plan.paymentReminderDays,
            planKind = plan.planKind.name,
            symptoms = parseSymptoms(plan.symptoms),
            totalMinor = total,
            paidMinor = paid,
            owedMinor = (total - paid).coerceAtLeast(0),
            currency = cur,
            planPaymentStatus = planPaymentLabel(total, paid),
            createdAt = plan.createdAt.toString(),
            updatedAt = plan.updatedAt.toString(),
        )
    }

    private fun toLineDetail(line: TreatmentPlanLine): LineDetailDto {
        val appt = line.linkedAppointment
        val apptDto = appt?.let {
            AppointmentSummaryDto(
                id = it.id,
                startAt = it.startAt.toString(),
                endAt = it.endAt.toString(),
                status = it.status.name,
                doctorProfileId = it.doctor.id!!,
                doctorName = "${it.doctor.firstName} ${it.doctor.lastName}",
            )
        }
        return LineDetailDto(
            id = line.id,
            catalogItemId = line.catalogItem?.id,
            title = line.title,
            quantity = line.quantity,
            unitPriceMinor = line.unitPriceMinor,
            discountMinor = line.discountMinor,
            currency = line.currency,
            sortOrder = line.sortOrder,
            status = line.status.name,
            linkedAppointment = apptDto,
            lineTotalMinor = lineAmt(line),
        )
    }

    private fun toDetail(plan: TreatmentPlan): TreatmentPlanDetailDto {
        val summaries = installmentPlans.findByTreatmentPlan_IdOrderByCreatedAtDesc(plan.id).map { ip ->
            InstallmentSummaryDto(
                installmentPlanId = ip.id,
                status = ip.status.name,
                totalAmountMinor = ip.totalAmountMinor,
                currency = ip.currency,
                numInstallments = ip.numInstallments,
            )
        }
        val lines = linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(plan.id).map { toLineDetail(it) }
        return TreatmentPlanDetailDto(
            summary = toSummary(plan),
            lines = lines,
            installmentPlans = summaries,
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
            treatmentPlanId = plan.id,
        )
        val saved = notifications.save(n)
        patient.fcmToken?.let {
            fcmService.sendPatientNotification(
                it,
                saved,
                mapOf("route" to "/bookings/treatment-plan/${plan.id}"),
            )
        }
    }

    private fun toCatalogItemDto(item: TreatmentPlanCatalogItem): CatalogItemDto {
        val assigned =
            if (item.appliesToAllDoctors) {
                emptyList()
            } else {
                clinicCatalog.explicitAssignmentIds(item.id)
            }
        return CatalogItemDto(
            id = item.id,
            clinicId = item.clinic.id!!,
            code = item.code,
            title = item.title,
            defaultPriceMinor = item.defaultPriceMinor,
            currency = item.currency,
            active = item.active,
            sortOrder = item.sortOrder,
            appliesToAllDoctors = item.appliesToAllDoctors,
            assignedDoctorProfileIds = assigned,
        )
    }

    /**
     * Clinic-wide plan list with optional patient / status / free-text filters.
     *
     * - When [patientId] is supplied the result is scoped to that patient (legacy
     *   behaviour required by the per-patient view).
     * - Otherwise every plan in the clinic is returned, ordered by most-recent
     *   update first, so the doctor app's "Treatment plans" tab can render a
     *   ledger without forcing the user to pick a patient first.
     * - [status] filters by [TreatmentPlan.Status] case-insensitively.
     * - [q] is a free-text needle matched against the plan title and the
     *   patient's full name.
     */
    @GetMapping
    @Transactional(readOnly = true)
    fun listPlans(
        @AuthenticationPrincipal principal: Any,
        @RequestParam clinicId: Long,
        @RequestParam(required = false) patientId: Long? = null,
        @RequestParam(required = false) status: String? = null,
        @RequestParam(required = false) q: String? = null,
    ): List<TreatmentPlanSummaryDto> {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)

        val source = if (patientId != null) {
            clinicAccess.assertPatientVisible(principal, patientId)
            plans.findByClinic_IdAndPatient_IdOrderByCreatedAtDesc(clinicId, patientId)
        } else {
            plans.findByClinic_IdOrderByUpdatedAtDescIdDesc(clinicId)
        }

        val statusFilter = status?.trim()?.takeIf { it.isNotEmpty() }
        val needle = q?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()

        return source
            .asSequence()
            .filter { plan ->
                if (statusFilter == null) true
                else plan.status.name.equals(statusFilter, ignoreCase = true)
            }
            .filter { plan ->
                if (needle == null) return@filter true
                val title = plan.title?.lowercase().orEmpty()
                val pname = plan.patient.fullName.lowercase()
                title.contains(needle) || pname.contains(needle)
            }
            .map { toSummary(it) }
            .toList()
    }

    @GetMapping("/catalog-items")
    fun listCatalog(
        @AuthenticationPrincipal principal: Any,
        @RequestParam clinicId: Long,
    ): List<CatalogItemDto> {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        return catalogRepo.findByClinic_IdOrderByActiveDescSortOrderAscIdAsc(clinicId).map { toCatalogItemDto(it) }
    }

    @PostMapping("/catalog-items")
    @Transactional
    fun createCatalogItem(
        @AuthenticationPrincipal principal: Any,
        @RequestBody @Valid req: UpsertCatalogItemRequest,
    ): CatalogItemDto {
        clinicAccess.assertPrincipalMayAccessClinic(principal, req.clinicId)
        val clinic = clinics.findById(req.clinicId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Clinic not found")
        }
        clinicCatalog.validateAssignment(
            req.clinicId,
            req.appliesToAllDoctors,
            req.assignedDoctorProfileIds,
        )
        val saved = catalogRepo.save(
            TreatmentPlanCatalogItem(
                clinic = clinic,
                code = req.code?.trim()?.takeIf { it.isNotEmpty() },
                title = req.title.trim(),
                defaultPriceMinor = req.defaultPriceMinor,
                currency = req.currency,
                active = req.active,
                sortOrder = req.sortOrder,
                appliesToAllDoctors = req.appliesToAllDoctors,
            ),
        )
        if (req.appliesToAllDoctors) {
            clinicCatalog.replaceExplicitAssignments(saved, emptyList())
        } else {
            clinicCatalog.replaceExplicitAssignments(saved, req.assignedDoctorProfileIds)
        }
        clinicCatalog.syncCatalogItemToDoctorServices(saved)
        return toCatalogItemDto(saved)
    }

    @PatchMapping("/catalog-items/{catalogItemId}")
    @Transactional
    fun patchCatalogItem(
        @AuthenticationPrincipal principal: Any,
        @PathVariable catalogItemId: Long,
        @RequestBody body: PatchCatalogItemRequest,
    ): CatalogItemDto {
        val item = catalogRepo.findById(catalogItemId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Catalog item not found")
        }
        val clinicId = item.clinic.id!!
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)

        body.code?.let { raw ->
            item.code = raw.trim().takeIf { it.isNotEmpty() }
        }
        body.title?.let {
            val t = it.trim()
            if (t.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Title required")
            item.title = t
        }
        body.defaultPriceMinor?.let { item.defaultPriceMinor = it }
        body.currency?.let { c ->
            val t = c.trim()
            if (t.isNotEmpty()) item.currency = t
        }
        body.active?.let { item.active = it }
        body.sortOrder?.let { item.sortOrder = it }
        item.updatedAt = OffsetDateTime.now()

        when {
            body.appliesToAllDoctors == true -> {
                item.appliesToAllDoctors = true
                clinicCatalog.replaceExplicitAssignments(item, emptyList())
            }
            body.assignedDoctorProfileIds != null -> {
                clinicCatalog.validateAssignment(clinicId, false, body.assignedDoctorProfileIds)
                item.appliesToAllDoctors = false
                clinicCatalog.replaceExplicitAssignments(item, body.assignedDoctorProfileIds)
            }
            body.appliesToAllDoctors == false -> {
                if (item.appliesToAllDoctors) {
                    val ids =
                        body.assignedDoctorProfileIds ?: throw ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "assignedDoctorProfileIds required when switching off all-doctors mode",
                        )
                    clinicCatalog.validateAssignment(clinicId, false, ids)
                    item.appliesToAllDoctors = false
                    clinicCatalog.replaceExplicitAssignments(item, ids)
                }
            }
        }

        catalogRepo.save(item)
        clinicCatalog.syncCatalogItemToDoctorServices(item)
        return toCatalogItemDto(item)
    }

    @PostMapping
    @Transactional
    fun createPlan(
        @AuthenticationPrincipal principal: Any,
        @RequestBody @Valid req: CreatePlanRequest,
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
        val symptomsJson = req.symptoms?.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) }
        val plan = plans.save(
            TreatmentPlan(
                clinic = clinic,
                patient = patient,
                attendingDoctor = attending,
                status = TreatmentPlan.Status.DRAFT,
                title = req.title?.trim(),
                symptoms = symptomsJson,
                planKind = req.planKind ?: TreatmentPlan.PlanKind.COMPREHENSIVE,
                diagnosis = req.diagnosis?.trim(),
                notes = req.notes?.trim(),
                paymentReminderDays = req.paymentReminderDays,
                createdByUser = user,
            ),
        )
        return toSummary(plan)
    }

    @GetMapping("/{planId}")
    fun getPlan(
        @AuthenticationPrincipal principal: Any,
        @PathVariable planId: Long,
    ): TreatmentPlanSummaryDto {
        val plan = plans.findById(planId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found")
        }
        clinicAccess.assertPrincipalMayAccessClinic(principal, plan.clinic.id!!)
        clinicAccess.assertPatientVisible(principal, plan.patient.id!!)
        return toSummary(plan)
    }

    @GetMapping("/{planId}/detail")
    @Transactional(readOnly = true)
    fun getPlanDetail(
        @AuthenticationPrincipal principal: Any,
        @PathVariable planId: Long,
    ): TreatmentPlanDetailDto {
        val plan = plans.findById(planId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found")
        }
        clinicAccess.assertPrincipalMayAccessClinic(principal, plan.clinic.id!!)
        clinicAccess.assertPatientVisible(principal, plan.patient.id!!)
        return toDetail(plan)
    }

    @PatchMapping("/{planId}")
    @Transactional
    fun patchPlan(
        @AuthenticationPrincipal principal: Any,
        @PathVariable planId: Long,
        @RequestBody body: PatchPlanRequest,
    ): TreatmentPlanSummaryDto {
        val plan = plans.findById(planId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found")
        }
        clinicAccess.assertPrincipalMayAccessClinic(principal, plan.clinic.id!!)
        clinicAccess.assertPatientVisible(principal, plan.patient.id!!)
        body.title?.let { plan.title = it.trim().takeIf { s -> s.isNotEmpty() } }
        body.diagnosis?.let { plan.diagnosis = it.trim().takeIf { s -> s.isNotEmpty() } }
        body.notes?.let { plan.notes = it.trim().takeIf { s -> s.isNotEmpty() } }
        body.paymentReminderDays?.let { plan.paymentReminderDays = it }
        body.attendingDoctorId?.let { aid ->
            val d = doctors.findById(aid).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
            clinicAccess.assertCanViewDoctorCalendar(principal, d.id!!)
            plan.attendingDoctor = d
        }
        body.symptoms?.let { s ->
            plan.symptoms = s.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) }
        }
        plan.updatedAt = OffsetDateTime.now()
        plans.save(plan)
        return toSummary(plan)
    }

    @PostMapping("/{planId}/link-appointments")
    @Transactional
    fun linkAppointments(
        @AuthenticationPrincipal principal: Any,
        @PathVariable planId: Long,
        @RequestBody body: List<LinkAppointmentReq>,
    ): TreatmentPlanDetailDto {
        val plan = plans.findById(planId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found")
        }
        clinicAccess.assertPrincipalMayAccessClinic(principal, plan.clinic.id!!)
        clinicAccess.assertPatientVisible(principal, plan.patient.id!!)

        for (pair in body) {
            val line = linesRepo.findById(pair.lineId).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found")
            }
            if (line.plan.id != planId) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Line not on this plan")

            val oldAppt = line.linkedAppointment
            oldAppt?.let { a ->
                if (a.linkedTreatmentPlanLine?.id == line.id) {
                    a.linkedTreatmentPlanLine = null
                    appts.save(a)
                }
            }

            if (pair.appointmentId == null) {
                line.linkedAppointment = null
                linesRepo.save(line)
                continue
            }

            val appt = appts.findById(pair.appointmentId).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found")
            }
            if (appt.patient.id != plan.patient.id) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Appointment patient mismatch")
            }
            clinicAccess.assertCanViewDoctorCalendar(principal, appt.doctor.id!!)
            line.linkedAppointment = appt
            linesRepo.save(line)
            appt.linkedTreatmentPlanLine = line
            appts.save(appt)
        }

        plan.updatedAt = OffsetDateTime.now()
        plans.save(plan)
        notifyPatient(plan, Notification.Type.TREATMENT_PLAN_UPDATED, "Treatment plan updated", "Your clinic linked visits to your treatment plan.")
        return toDetail(plans.findById(planId).orElseThrow())
    }

    @PostMapping("/{planId}/lines")
    @Transactional
    fun replaceLines(
        @AuthenticationPrincipal principal: Any,
        @PathVariable planId: Long,
        @RequestBody body: List<LineReq>,
    ): TreatmentPlanSummaryDto {
        val plan = plans.findById(planId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found")
        }
        clinicAccess.assertPrincipalMayAccessClinic(principal, plan.clinic.id!!)
        clinicAccess.assertPatientVisible(principal, plan.patient.id!!)

        linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(planId).forEach { line ->
            line.linkedAppointment?.let { a ->
                if (a.linkedTreatmentPlanLine?.id == line.id) {
                    a.linkedTreatmentPlanLine = null
                    appts.save(a)
                }
            }
            linesRepo.delete(line)
        }

        for (row in body) {
            val catalog = row.catalogItemId?.let {
                catalogRepo.findById(it).orElseThrow { ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad catalog item") }
                    .also { c ->
                        if (c.clinic.id != plan.clinic.id) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Catalog mismatch")
                    }
            }
            val linkedAppt = row.linkedAppointmentId?.let { aid ->
                val a = appts.findById(aid).orElseThrow { ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad appointment") }
                if (a.patient.id != plan.patient.id) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Appointment patient mismatch")
                clinicAccess.assertCanViewDoctorCalendar(principal, a.doctor.id!!)
                a
            }
            val saved = linesRepo.save(
                TreatmentPlanLine(
                    plan = plan,
                    catalogItem = catalog,
                    title = row.title.trim(),
                    quantity = row.quantity,
                    unitPriceMinor = row.unitPriceMinor,
                    discountMinor = row.discountMinor,
                    currency = row.currency,
                    sortOrder = row.sortOrder,
                    linkedAppointment = linkedAppt,
                    status = row.status ?: TreatmentPlanLine.LineStatus.PLANNED,
                ),
            )
            if (linkedAppt != null) {
                linkedAppt.linkedTreatmentPlanLine = saved
                appts.save(linkedAppt)
            }
        }
        plan.updatedAt = OffsetDateTime.now()
        plans.save(plan)
        financeService.recalculatePlanTotals(planId)
        notifyPatient(plan, Notification.Type.TREATMENT_PLAN_UPDATED, "Treatment plan updated", "Your clinic updated your treatment plan. Please review payment details in the app.")
        return toSummary(plans.findById(planId).orElseThrow())
    }

    @PatchMapping("/{planId}/status")
    @Transactional
    fun patchStatus(
        @AuthenticationPrincipal principal: Any,
        @PathVariable planId: Long,
        @RequestBody @Valid body: PatchPlanStatusRequest,
    ): TreatmentPlanSummaryDto {
        val plan = plans.findById(planId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found")
        }
        clinicAccess.assertPrincipalMayAccessClinic(principal, plan.clinic.id!!)
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
        @RequestBody @Valid body: PaymentReq,
    ): TreatmentPlanSummaryDto {
        val plan = plans.findById(planId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found")
        }
        clinicAccess.assertPrincipalMayAccessClinic(principal, plan.clinic.id!!)
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
                recordedByUser = user,
            ),
        )
        plan.updatedAt = OffsetDateTime.now()
        plans.save(plan)
        financeService.recalculatePlanTotals(planId)
        val refreshedPlan = plans.findById(planId).orElseThrow()
        val owed = refreshedPlan.remainingAmountMinor
        if (owed > 0) {
            notifyPatient(
                plan,
                Notification.Type.TREATMENT_PLAN_PAYMENT_REMINDER,
                "Payment pending",
                "You have an outstanding balance on your treatment plan. Please arrange payment at the clinic.",
            )
        }
        return toSummary(plan)
    }
}
