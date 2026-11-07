package com.shifa.doctors.api.dto

/**
 * `GET /api/doctors/me/contract`: the doctor's early-partner contract. Dates `yyyy-MM-dd` (UTC calendar),
 * [endDate] = [effectiveDate] + [termMonths]; [issuedAt] an instant; [status] `ACTIVE` until the end date, then `EXPIRED`.
 */
data class DoctorContractDto(
    val contractNumber: String,
    val effectiveDate: String,
    val endDate: String,
    val termMonths: Int,
    val issuedAt: String,
    val status: String,
)
