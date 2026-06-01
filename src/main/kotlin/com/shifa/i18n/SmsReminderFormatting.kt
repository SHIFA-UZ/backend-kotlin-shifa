package com.shifa.i18n

import java.time.Instant
import java.time.ZoneId

/**
 * DevSMS-approved appointment reminder template (Uzbek):
 * Hurmatli {patient}, {d}-{month} {y} soat {HH:mm} ga shifokor {doctor} qabuliga yozilgansiz.
 * Iltimos qabulga vaqtida boring. Rahmat, SHIFA.UZ
 */
object SmsReminderFormatting {

    private val uzMonths = listOf(
        "yanvar", "fevral", "mart", "aprel", "may", "iyun",
        "iyul", "avgust", "sentyabr", "oktyabr", "noyabr", "dekabr",
    )

    fun appointmentReminderBody(
        patientName: String,
        doctorName: String,
        startAt: Instant,
        timeZone: String,
    ): String {
        val patient = patientName.trim().ifEmpty { "Bemor" }
        val doctor = doctorName.trim().ifEmpty { "shifokor" }
        val (datePart, timePart) = uzDateAndTimeForSms(startAt, timeZone)
        return "Hurmatli $patient, $datePart soat $timePart ga shifokor $doctor qabuliga yozilgansiz. " +
            "Iltimos qabulga vaqtida boring. Rahmat, SHIFA.UZ"
    }

    /** Same approved template as real reminders (used for profile test SMS). */
    fun testReminderBody(
        patientName: String,
        doctorName: String,
        startAt: Instant,
        timeZone: String,
    ): String = appointmentReminderBody(patientName, doctorName, startAt, timeZone)

    internal fun uzDateAndTimeForSms(startAt: Instant, timeZone: String): Pair<String, String> {
        val zone = runCatching { ZoneId.of(timeZone) }.getOrElse { ZoneId.of("UTC") }
        val z = startAt.atZone(zone)
        val monthUz = uzMonths[z.monthValue - 1]
        val datePart = "${z.dayOfMonth}-$monthUz ${z.year}"
        val timePart =
            "${z.hour.toString().padStart(2, '0')}:${z.minute.toString().padStart(2, '0')}"
        return datePart to timePart
    }
}
