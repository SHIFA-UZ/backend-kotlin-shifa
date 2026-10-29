package com.shifa.clinics.api.dto

import com.shifa.clinics.api.TreatmentPlanController.LineDetailDto
import com.shifa.clinics.domain.TreatmentPlan
import com.shifa.clinics.domain.TreatmentPlanLine
import com.shifa.clinics.domain.TreatmentPlanPayment
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull


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
    val groupId: Long? = null,
    val groupName: String? = null,
    val groupSortOrder: Int? = null,
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
    /** Distinct appointments linked via plan lines (scheduled or completed visits). */
    val visitCount: Int,
    val linesCompletedCount: Int,
    val linesTotalCount: Int,
    val createdAt: String?,
    val updatedAt: String?,
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
    val dentalPlanDocumentation: String?,
)

data class FulfillmentCandidateDto(
    val lineId: Long,
    val title: String,
    val fdi: String?,
    val dentition: String?,
    val unitPriceMinor: Long,
    val quantity: Int,
    val discountMinor: Long,
    val currency: String,
    val lineTotalMinor: Long,
    val status: String,
    val toothMatch: Boolean,
)

data class FulfillPlanRequest(val lineIds: List<Long>)

data class AppendLinesRequest(
    val appointmentId: Long? = null,
    val lines: List<LineReq>,
)

/** One appointment linked to a comprehensive treatment plan (past, upcoming, or cancelled). */
data class PlanVisitDto(
    val appointmentId: Long,
    val startAt: String,
    val endAt: String,
    val status: String,
    val doctorProfileId: Long,
    val doctorName: String,
    val location: String,
    val services: List<String>,
    val visitTotalMinor: Long,
    val visitCollectedMinor: Long,
    val visitOwedMinor: Long,
    val currency: String,
    val visitPaymentStatus: String,
    /** UPCOMING | PAST | CANCELLED — used by the doctor app for grouping / badges. */
    val timing: String,
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
    val specialtyMetadata: String? = null,
    val assignedDoctorId: Long? = null,
    val notes: String? = null,
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
    val dentalPlanDocumentation: String? = null,
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
    val dentalPlanDocumentation: String? = null,
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

data class PlanDoctorLocationDto(
    val id: Long,
    val label: String,
    val clinic: String?,
    val address: String?,
    val isPrimary: Boolean,
)
