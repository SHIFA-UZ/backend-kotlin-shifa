package com.shifa.clinics.api.dto

import com.shifa.clinics.domain.TreatmentPlanPayment
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.LocalDate
import java.time.OffsetDateTime

data class FinanceDashboardDto(
    val totalRevenueMinor: Long,
    val outstandingMinor: Long,
    val overdueCount: Int,
    val collectionRate: Double,
    val currency: String,
    val doctorEarningsTop: List<DoctorEarningRowDto>,
    val totalDoctorShareGrossMinor: Long? = null,
    val totalClinicShareGrossMinor: Long? = null,
    val totalDoctorShareCollectedMinor: Long? = null,
    val totalClinicShareCollectedMinor: Long? = null,
)

data class DoctorEarningRowDto(
    val doctorProfileId: Long,
    val visitCount: Int,
    val grossMinor: Long,
    val collectedMinor: Long,
    val outstandingMinor: Long,
    val currency: String,
    val revenueSharePercent: Int? = null,
    val doctorShareGrossMinor: Long? = null,
    val clinicShareGrossMinor: Long? = null,
    val doctorShareCollectedMinor: Long? = null,
    val clinicShareCollectedMinor: Long? = null,
)

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
