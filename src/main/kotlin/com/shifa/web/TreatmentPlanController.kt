package com.shifa.web

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.domain.Appointment
import com.shifa.domain.Notification
import com.shifa.domain.TreatmentPlan
import com.shifa.domain.TreatmentPlanLine
import com.shifa.domain.TreatmentPlanPayment
import com.shifa.domain.TreatmentPlanCatalogItem
import com.shifa.repo.*
import com.shifa.repo.DoctorLocationRepository
import com.shifa.service.ClinicAccessService
import com.shifa.service.ClinicCatalogService
import com.shifa.service.FcmService
import com.shifa.service.PatientDaySlotsService
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
    private val installmentItems: InstallmentItemRepository,
    private val users: UserRepository,
    private val notifications: NotificationRepository,
    private val fcmService: FcmService,
    private val objectMapper: ObjectMapper,
    private val doctorLocations: DoctorLocationRepository,
    private val patientDaySlots: PatientDaySlotsService,
    private val doctorServices: DoctorServiceRepository,
    private val doctorServicePrices: DoctorServicePriceRepository,
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

    /**
     * Unified service option for the clinic Services tab and the treatment-plan
     * wizard. Each row is either a clinic-managed catalog item or a
     * doctor-defined service from the doctor's profile (when that service is
     * not already mirroring a clinic catalog item).
     *
     * - [kind] = "CLINIC_CATALOG" — sourced from `treatment_plan_catalog_items`,
     *   shared across the clinic. `catalogItemId` is set.
     * - [kind] = "DOCTOR_SERVICE" — sourced from `doctor_services` (a single
     *   doctor's own catalog row). `doctorServiceId` is set.
     *
     * [offeredByDoctorIds]/[offeredByDoctorNames] tell the UI which doctors of
     * the clinic offer this service so it can render doctor tags on every line.
     * The lists carry the **same length and the same order** (paired entries).
     */
    data class PlanServiceOptionDto(
        /** Stable unique key for UI list rendering, e.g. "catalog:42" or "doctor:7:service:123". */
        val key: String,
        val kind: String,
        val catalogItemId: Long?,
        val doctorServiceId: Long?,
        val title: String,
        val code: String?,
        val defaultPriceMinor: Long,
        val currency: String,
        val active: Boolean,
        val offeredByDoctorIds: List<Long>,
        val offeredByDoctorNames: List<String>,
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
        val attendingDoctors: List<DoctorRef>,
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

    data class InstallmentScheduleRowDto(
        /** Stable id used by Finance → installments. */
        val installmentItemId: Long,
        val sequenceNumber: Int,
        val dueDate: String,
        val amountMinor: Long,
        val currency: String,
        val status: String,
    )

    data class InstallmentSummaryDto(
        val installmentPlanId: Long,
        val status: String,
        val totalAmountMinor: Long,
        val currency: String,
        val numInstallments: Int,
        val scheduleRows: List<InstallmentScheduleRowDto> = emptyList(),
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
        /** Optional full list of doctors attached to this plan. */
        val attendingDoctorIds: List<Long>? = null,
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
        /** When non-null replaces the full doctor list on the plan. */
        val attendingDoctorIds: List<Long>? = null,
        val symptoms: List<String>? = null,
    )

    data class DoctorRef(val id: Long, val name: String)

    data class BookSlotReq(
        @field:NotNull val doctorId: Long,
        /** ISO-8601 instant, e.g. "2026-06-12T09:00:00Z". */
        @field:NotBlank val startAt: String,
        @field:Min(5) val slotMinutes: Int = 30,
        val locationId: Long? = null,
        /** When set, the booked appointment is also linked to this plan line. */
        val lineId: Long? = null,
        val notes: String? = null,
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
        val doctorRefs = plan.attendingDoctors
            .map { DoctorRef(it.id!!, "${it.firstName} ${it.lastName}".trim()) }
            .sortedBy { it.name.lowercase() }
        return TreatmentPlanSummaryDto(
            id = plan.id,
            clinicId = plan.clinic.id!!,
            patientId = plan.patient.id,
            patientName = patientName,
            attendingDoctorId = doctor?.id,
            attendingDoctorName = doctorName,
            attendingDoctors = doctorRefs,
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
            val schedule =
                installmentItems.findByInstallmentPlan_IdOrderBySequenceNumberAsc(ip.id).map { ii ->
                    InstallmentScheduleRowDto(
                        installmentItemId = ii.id,
                        sequenceNumber = ii.sequenceNumber,
                        dueDate = ii.dueDate.toString(),
                        amountMinor = ii.amountMinor,
                        currency = ii.currency,
                        status = ii.status.name,
                    )
                }
            InstallmentSummaryDto(
                installmentPlanId = ip.id,
                status = ip.status.name,
                totalAmountMinor = ip.totalAmountMinor,
                currency = ip.currency,
                numInstallments = ip.numInstallments,
                scheduleRows = schedule,
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
        // Patient-only notification: do NOT tag the attending doctor here.
        // The doctor row is filtered by `doctor_id` in NotificationController, so
        // including the doctor would surface a patient-facing message in the
        // doctor's feed (e.g. "Your treatment plan status is now CANCELLED").
        val n = Notification(
            patient = patient,
            doctor = null,
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

    /**
     * Unified service catalog combining the clinic-managed catalog with
     * doctor-defined profile services.
     *
     * - When [doctorIds] is null/empty, returns the full set: every clinic
     *   catalog item + every doctor-only service (not mirroring a catalog row)
     *   for every doctor practising at the clinic. Used by the Clinic →
     *   Services tab to show the full picture (so doctor-defined services
     *   appear without forcing them through clinic-level provisioning first).
     * - When [doctorIds] is supplied, returns the union of:
     *     - catalog items that apply to at least one of those doctors
     *       (either `appliesToAllDoctors = true`, or one of the doctors is in
     *       the explicit assignment list), and
     *     - doctor-only services from those same doctors.
     *   Used by the treatment-plan wizard so the service picker is filtered to
     *   what the selected attending doctors can actually deliver, and each
     *   line shows a doctor tag.
     *
     * By default inactive doctor services are skipped; clinic catalog rows are
     * always returned (the UI greys out inactive ones), matching the existing
     * `/catalog-items` behaviour.
     */
    @GetMapping("/plan-services")
    @Transactional(readOnly = true)
    fun listPlanServices(
        @AuthenticationPrincipal principal: Any,
        @RequestParam clinicId: Long,
        @RequestParam(required = false) doctorIds: List<Long>? = null,
        @RequestParam(required = false, defaultValue = "false") includeInactiveDoctorServices: Boolean,
    ): List<PlanServiceOptionDto> {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)

        val practiceDoctors = doctors.findAllByPracticeClinic_Id(clinicId)
        val practiceIds = practiceDoctors.mapNotNull { it.id }.toSet()
        val nameByDoctorId: Map<Long, String> = practiceDoctors.associate { d ->
            (d.id ?: 0L) to "${d.firstName} ${d.lastName}".trim()
        }

        val doctorIdFilter = doctorIds?.toSet()?.takeIf { it.isNotEmpty() }
        if (doctorIdFilter != null) {
            val unknown = doctorIdFilter - practiceIds
            if (unknown.isNotEmpty()) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Doctor(s) not in clinic practice: ${unknown.joinToString()}",
                )
            }
        }

        val out = mutableListOf<PlanServiceOptionDto>()

        // --- Clinic catalog items -------------------------------------------
        val catalogItems = catalogRepo.findByClinic_IdOrderByActiveDescSortOrderAscIdAsc(clinicId)
        for (item in catalogItems) {
            val offeredBy: Set<Long> = if (item.appliesToAllDoctors) {
                practiceIds
            } else {
                clinicCatalog.explicitAssignmentIds(item.id).toSet()
            }
            if (doctorIdFilter != null && offeredBy.intersect(doctorIdFilter).isEmpty()) {
                continue
            }
            val offeredIds = offeredBy.toList().sorted()
            out.add(
                PlanServiceOptionDto(
                    key = "catalog:${item.id}",
                    kind = "CLINIC_CATALOG",
                    catalogItemId = item.id,
                    doctorServiceId = null,
                    title = item.title,
                    code = item.code,
                    defaultPriceMinor = item.defaultPriceMinor,
                    currency = item.currency,
                    active = item.active,
                    offeredByDoctorIds = offeredIds,
                    offeredByDoctorNames = offeredIds.map { id ->
                        nameByDoctorId[id]?.takeIf { it.isNotEmpty() } ?: "Doctor #$id"
                    },
                ),
            )
        }

        // --- Doctor-only services (not synced from a clinic catalog item) ---
        val targetDoctorIds = doctorIdFilter ?: practiceIds
        for (doctorId in targetDoctorIds) {
            val displayName = nameByDoctorId[doctorId]?.takeIf { it.isNotEmpty() }
                ?: "Doctor #$doctorId"
            val services = doctorServices.findByDoctorIdOrderByCreatedAtAsc(doctorId)
            for (svc in services) {
                if (svc.sourceCatalogItem != null) continue // already represented by a CLINIC_CATALOG row
                if (!includeInactiveDoctorServices && !svc.isActive) continue

                val price = pickDoctorServicePrice(svc.id)
                val amountMinor = price?.amountMinor ?: 0L
                val currency = price?.currency
                    ?: svc.sourceCatalogItem?.currency
                    ?: "UZS"
                out.add(
                    PlanServiceOptionDto(
                        key = "doctor:$doctorId:service:${svc.id}",
                        kind = "DOCTOR_SERVICE",
                        catalogItemId = null,
                        doctorServiceId = svc.id,
                        title = svc.title,
                        code = null,
                        defaultPriceMinor = amountMinor,
                        currency = currency,
                        active = svc.isActive,
                        offeredByDoctorIds = listOf(doctorId),
                        offeredByDoctorNames = listOf(displayName),
                    ),
                )
            }
        }

        // Stable display ordering: clinic catalog first (preserving repo
        // ordering), then doctor services grouped by doctor name then title.
        return out.sortedWith(
            compareBy<PlanServiceOptionDto> { it.kind != "CLINIC_CATALOG" }
                .thenBy { it.offeredByDoctorNames.firstOrNull()?.lowercase().orEmpty() }
                .thenBy { it.title.lowercase() },
        )
    }

    /**
     * Pick a representative price row for a doctor service. Prefers a row with
     * no `locationId` (the doctor's default) and falls back to the first row
     * the repo returns when only per-location prices exist. Used when surfacing
     * doctor services in the clinic-wide picker, where per-location pricing
     * isn't shown (the treatment-plan line stores a single unit price anyway).
     */
    private fun pickDoctorServicePrice(serviceId: Long): com.shifa.domain.DoctorServicePrice? {
        val rows = doctorServicePrices.findByService_IdOrderByCurrencyAsc(serviceId)
        if (rows.isEmpty()) return null
        return rows.firstOrNull { it.location == null } ?: rows.first()
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
        // Resolve every requested doctor and de-duplicate while keeping the
        // caller-provided order so the first id can play the role of the
        // back-compat "primary" attending doctor.
        val combinedIds = buildList {
            req.attendingDoctorIds?.let { addAll(it) }
            req.attendingDoctorId?.let { add(it) }
        }.distinct()
        val resolvedDoctors = combinedIds.map { docId ->
            val d = doctors.findById(docId).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor $docId not found")
            }
            clinicAccess.assertCanViewDoctorCalendar(principal, d.id!!)
            d
        }
        val attending = resolvedDoctors.firstOrNull()
        val uid = clinicAccess.resolveBookingActorUserId(principal)
        val user = users.findById(uid).orElse(null)
        val symptomsJson = req.symptoms?.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) }
        val plan = plans.save(
            TreatmentPlan(
                clinic = clinic,
                patient = patient,
                attendingDoctor = attending,
                attendingDoctors = resolvedDoctors.toMutableSet(),
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
            // Ensure the primary is also part of the explicit doctor set.
            if (plan.attendingDoctors.none { it.id == d.id }) plan.attendingDoctors.add(d)
        }
        body.attendingDoctorIds?.let { ids ->
            val resolved = ids.distinct().map { docId ->
                val d = doctors.findById(docId).orElseThrow {
                    ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor $docId not found")
                }
                clinicAccess.assertCanViewDoctorCalendar(principal, d.id!!)
                d
            }
            plan.attendingDoctors.clear()
            plan.attendingDoctors.addAll(resolved)
            // Keep primary in sync: prefer the previously-set primary if it's
            // still in the list, otherwise fall back to the first entry.
            val primaryStillPresent = plan.attendingDoctor?.let { current ->
                resolved.any { it.id == current.id }
            } ?: false
            if (!primaryStillPresent) plan.attendingDoctor = resolved.firstOrNull()
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

    // -- Free-slot lookup + multi-slot booking for the plan wizard -----------

    data class PlanDoctorLocationDto(
        val id: Long,
        val label: String,
        val clinic: String?,
        val address: String?,
        val isPrimary: Boolean,
    )

    /**
     * Practice locations for [doctorId], for the treatment-plan wizard slot
     * picker. When a doctor has several locations the client must choose one
     * before loading free slots for that site.
     */
    @GetMapping("/doctor-locations")
    @Transactional(readOnly = true)
    fun doctorLocations(
        @AuthenticationPrincipal principal: Any,
        @RequestParam clinicId: Long,
        @RequestParam doctorId: Long,
    ): List<PlanDoctorLocationDto> {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        clinicAccess.assertCanViewDoctorCalendar(principal, doctorId)
        val doctor = doctors.findById(doctorId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found")
        }
        val structured = doctorLocations.findByDoctorIdOrderByIsPrimaryDescIdAsc(doctor.id!!)
        if (structured.isNotEmpty()) {
            return structured.map { loc ->
                PlanDoctorLocationDto(
                    id = loc.id!!,
                    label = loc.label,
                    clinic = loc.clinic,
                    address = loc.address,
                    isPrimary = loc.isPrimary,
                )
            }
        }
        val hasLegacy = !doctor.clinic.isNullOrBlank() ||
            !doctor.address.isNullOrBlank() ||
            doctor.latitude != null ||
            !doctor.locationCity.isNullOrBlank()
        if (!hasLegacy) return emptyList()
        return listOf(
            PlanDoctorLocationDto(
                id = -1L,
                label = doctor.clinic ?: "Main clinic",
                clinic = doctor.clinic,
                address = doctor.address,
                isPrimary = true,
            ),
        )
    }

    /**
     * Returns bookable free slots for [doctorId] on [day]. Wraps
     * [PatientDaySlotsService] but is exposed under the treatment-plan namespace
     * with a non-patient principal so the doctor app's plan wizard can browse
     * availability for any doctor in the clinic.
     */
    @GetMapping("/free-slots")
    @Transactional(readOnly = true)
    fun freeSlots(
        @AuthenticationPrincipal principal: Any,
        @RequestParam clinicId: Long,
        @RequestParam doctorId: Long,
        @RequestParam day: String,
        @RequestParam(required = false) locationId: Long?,
    ): List<PatientDaySlotsService.AvailableSlotDto> {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        clinicAccess.assertCanViewDoctorCalendar(principal, doctorId)
        val doctor = doctors.findById(doctorId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found")
        }
        val date = try {
            java.time.LocalDate.parse(day)
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid day (expected YYYY-MM-DD)")
        }
        return patientDaySlots.availableSlotsForDay(doctor, date, locationId)
    }

    /**
     * Books a batch of free slots against this plan's patient atomically.
     *
     * Each entry creates a [Appointment] for `plan.patient` with the requested
     * doctor at the requested instant, status [Appointment.Status.CONFIRMED]
     * (the clinic is booking on the patient's behalf so there is no need for
     * a separate confirmation step). If [BookSlotReq.lineId] is supplied the
     * appointment is also linked to that plan line, and conflict detection
     * mirrors the patient self-booking endpoint so two patients can't end up
     * in the same slot.
     */
    @PostMapping("/{planId}/book-slots")
    @Transactional
    fun bookSlots(
        @AuthenticationPrincipal principal: Any,
        @PathVariable planId: Long,
        @RequestBody @Valid body: List<BookSlotReq>,
    ): TreatmentPlanDetailDto {
        if (body.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No slots to book")
        }
        val plan = plans.findById(planId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found")
        }
        clinicAccess.assertPrincipalMayAccessClinic(principal, plan.clinic.id!!)
        clinicAccess.assertPatientVisible(principal, plan.patient.id!!)
        val uid = clinicAccess.resolveBookingActorUserId(principal)
        val bookedBy = users.findById(uid).orElse(null)

        for (slot in body) {
            val doctor = doctors.findById(slot.doctorId).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor ${slot.doctorId} not found")
            }
            clinicAccess.assertCanViewDoctorCalendar(principal, doctor.id!!)

            val startInstant = try {
                java.time.Instant.parse(slot.startAt)
            } catch (e: Exception) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad startAt for slot")
            }
            val endInstant = startInstant.plusSeconds(slot.slotMinutes * 60L)

            if (appts.findOverlapping(doctor.id!!, startInstant, endInstant).isNotEmpty()) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Slot already taken for doctor ${doctor.id}")
            }
            if (appts.findOverlappingForPatient(plan.patient.id!!, startInstant, endInstant).isNotEmpty()) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Patient already has an appointment overlapping this slot")
            }

            // Pick the structured location either from the request or the
            // doctor's primary location, then resolve a human-readable label.
            val locationRef = slot.locationId?.let { lid ->
                doctorLocations.findById(lid).orElseThrow {
                    ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid locationId")
                }.also { dl ->
                    if (dl.doctor.id != doctor.id) {
                        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Location does not belong to doctor")
                    }
                }
            } ?: doctorLocations.findByDoctorIdAndIsPrimaryTrue(doctor.id!!).orElse(null)
                ?: doctorLocations.findByDoctorIdOrderByIsPrimaryDescIdAsc(doctor.id!!).firstOrNull()

            val locationLabel = locationRef?.clinic?.takeIf { it.isNotBlank() }
                ?: locationRef?.label
                ?: (doctor.clinic ?: "Clinic")

            val appt = Appointment(
                doctor = doctor,
                patient = plan.patient,
                bookedByUser = bookedBy,
                startAt = startInstant,
                endAt = endInstant,
                location = locationLabel,
                locationRef = locationRef,
                reason = slot.notes?.trim()?.takeIf { it.isNotEmpty() }
                    ?: plan.title?.let { "Treatment plan: $it" }
                    ?: "Treatment plan",
                status = Appointment.Status.CONFIRMED,
            )
            val savedAppt = appts.save(appt)

            slot.lineId?.let { lineId ->
                val line = linesRepo.findById(lineId).orElseThrow {
                    ResponseStatusException(HttpStatus.NOT_FOUND, "Line $lineId not found")
                }
                if (line.plan.id != planId) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Line does not belong to this plan")
                }
                line.linkedAppointment = savedAppt
                linesRepo.save(line)
                savedAppt.linkedTreatmentPlanLine = line
                appts.save(savedAppt)
            }

            // Ensure the slot's doctor is part of the plan's doctor set so the
            // plan-list UI surfaces every collaborator involved.
            if (plan.attendingDoctors.none { it.id == doctor.id }) {
                plan.attendingDoctors.add(doctor)
            }
        }

        plan.updatedAt = OffsetDateTime.now()
        plans.save(plan)
        notifyPatient(
            plan,
            Notification.Type.TREATMENT_PLAN_UPDATED,
            "Visits scheduled",
            "Your clinic scheduled new visits as part of your treatment plan.",
        )
        return toDetail(plans.findById(planId).orElseThrow())
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
