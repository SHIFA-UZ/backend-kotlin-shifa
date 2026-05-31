package com.shifa.i18n

import com.shifa.service.NotificationFormatting
import java.time.Instant

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

    /** Short immediate test SMS from patient profile (single SMS part). */
    fun testReminderBody(
        lang: String?,
        doctorName: String,
        doctorTimeZone: String,
    ): String {
        val doctor = doctorName.trim().ifEmpty { "—" }
        return when (code(lang)) {
            "uz" -> "Shifa test SMS. $doctor bilan eslatma xizmati yoqilgan."
            "ru" -> "Shifa тест SMS. Напоминания для $doctor включены."
            else -> "Shifa test SMS. Reminders for $doctor are enabled."
        }
    }
}
