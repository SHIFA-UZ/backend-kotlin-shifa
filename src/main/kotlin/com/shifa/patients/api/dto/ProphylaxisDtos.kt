package com.shifa.patients.api.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull

data class SettingDto(
    val patientId: Long?,
    val clinicId: Long,
    val intervalMonths: Int,
    val enabled: Boolean,
    val lastSentAt: String?
)

data class UpsertSettingRequest(
    @field:NotNull val patientId: Long,
    @field:NotNull val clinicId: Long,
    @field:Min(1) @field:Max(60) val intervalMonths: Int,
    val enabled: Boolean
)
