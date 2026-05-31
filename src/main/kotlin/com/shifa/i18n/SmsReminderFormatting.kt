package com.shifa.i18n

import com.shifa.service.NotificationFormatting
import java.time.Instant
import java.time.temporal.ChronoUnit

object SmsReminderFormatting {

    private fun code(lang: String?): String = when (lang?.lowercase()?.trim()?.substringBefore('-')?.take(2)) {
        "uz" -> "uz"
        "ru" -> "ru"
        else -> "en"
    }

    fun appointmentReminderBody(
        lang: String?,
        doctorName: String,
        startAt: Instant,
        timeZone: String,
        location: String,
    ): String {
        val (dateStr, timeStr) = NotificationFormatting.appointmentDateTimeStrings(startAt, timeZone)
        val doctor = doctorName.trim().ifEmpty { "—" }
        val place = location.trim().ifEmpty { "—" }
        return when (code(lang)) {
            "uz" -> "Ertaga $dateStr soat $timeStr da $doctor bilan uchrashingiz eslatmasi. Manzil: $place. Shifa"
            "ru" -> "Напоминание: завтра $dateStr в $timeStr приём у $doctor. Адрес: $place. Shifa"
            else -> "Reminder: tomorrow $dateStr at $timeStr appointment with $doctor. Location: $place. Shifa"
        }
    }

    /** Immediate test SMS from patient profile (sample reminder wording). */
    fun testReminderBody(
        lang: String?,
        doctorName: String,
        doctorTimeZone: String,
    ): String {
        val sampleStart = Instant.now().plus(1, ChronoUnit.DAYS)
        val sample = appointmentReminderBody(lang, doctorName, sampleStart, doctorTimeZone, "Klinika")
        return when (code(lang)) {
            "uz" -> "Shifa test SMS. Namuna: $sample"
            "ru" -> "Shifa тест SMS. Пример: $sample"
            else -> "Shifa test SMS. Sample: $sample"
        }
    }
}
