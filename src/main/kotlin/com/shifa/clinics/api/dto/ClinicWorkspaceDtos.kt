package com.shifa.clinics.api.dto

data class UpdateFinanceSettingsBody(val defaultDoctorRevenueSharePercent: Int? = null)

data class UpdateMemberRevenueShareBody(val doctorRevenueSharePercent: Int? = null)

data class VisitChargeLineBody(val catalogItemId: Long, val quantity: Int = 1, val unitPriceMinor: Long? = null)
