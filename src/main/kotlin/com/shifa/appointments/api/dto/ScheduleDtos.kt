package com.shifa.appointments.api.dto

/**
 * One weekly recurring rule. `locationId` is optional on the wire — when omitted, the
 * doctor's primary location is used. The patient app always sees one `locationId` per slot
 * so it can show "which place" a slot belongs to.
 */
data class RuleDto(
    val weekday: Int,
    val startTime: String,
    val endTime: String,
    val slotMinutes: Int,
    val locationId: Long? = null
)

data class ValidUntilDto(
    val validUntil: String
)

/** Request body for schedule validity range: from when until when (adds a new period) */
data class ValidRangeDto(
    val validFrom: String? = null,
    val validUntil: String
)

/** One validity period in the list response */
data class ValidityPeriodDto(
    val validFrom: String,
    val validUntil: String
)

data class DateSpecificRuleDto(
    val id: Long? = null,
    val startDate: String, // YYYY-MM-DD
    val endDate: String, // YYYY-MM-DD
    val startTime: String, // HH:mm
    val endTime: String, // HH:mm
    val slotMinutes: Int,
    val locationId: Long? = null
)
