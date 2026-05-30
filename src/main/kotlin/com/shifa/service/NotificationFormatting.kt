package com.shifa.service

import java.time.Instant
import java.time.ZoneId

object NotificationFormatting {
    fun appointmentDateTimeStrings(startAt: Instant, timeZone: String): Pair<String, String> {
        val zone = ZoneId.of(timeZone)
        val startZ = startAt.atZone(zone)
        val monthName = startZ.month.name.lowercase().replaceFirstChar { it.uppercase() }
        val dateStr = "${startZ.dayOfMonth} $monthName ${startZ.year}"
        val timeStr =
            "${startZ.hour.toString().padStart(2, '0')}:${startZ.minute.toString().padStart(2, '0')}"
        return dateStr to timeStr
    }

    fun patientBookedMessage(
        patientName: String,
        startAt: Instant,
        timeZone: String,
        suffix: String = "",
    ): String {
        val (dateStr, timeStr) = appointmentDateTimeStrings(startAt, timeZone)
        val extra = suffix.trim().let { if (it.isEmpty()) "" else " $it" }
        return "Patient $patientName booked an appointment for $dateStr at $timeStr.$extra"
    }
}
