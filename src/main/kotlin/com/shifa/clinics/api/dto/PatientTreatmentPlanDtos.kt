package com.shifa.clinics.api.dto

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
    val id: Long,
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
