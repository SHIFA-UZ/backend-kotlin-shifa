package com.shifa.i18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

class SmsReminderFormattingTest {

    @Test
    fun `uses DevSMS approved template with Uzbek date`() {
        val start = Instant.parse("2026-06-01T17:35:00Z") // 22:35 in Asia/Tashkent (UTC+5)
        val body = SmsReminderFormatting.appointmentReminderBody(
            patientName = "Bekzodbek Qobilov",
            doctorName = "Nigora Omarova",
            startAt = start,
            timeZone = "Asia/Tashkent",
        )
        assertEquals(
            "Hurmatli Bekzodbek Qobilov, 1-iyun 2026 soat 22:35 ga shifokor Nigora Omarova qabuliga yozilgansiz. " +
                "Iltimos qabulga vaqtida boring. Rahmat, SHIFA.UZ",
            body,
        )
    }

    @Test
    fun `uzDateAndTimeForSms formats day-month-year and time`() {
        val start = ZonedDateTimeOf(2026, 6, 1, 22, 35, "Asia/Tashkent")
        val (date, time) = SmsReminderFormatting.uzDateAndTimeForSms(start, "Asia/Tashkent")
        assertEquals("1-iyun 2026", date)
        assertEquals("22:35", time)
    }

    private fun ZonedDateTimeOf(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        zone: String,
    ): Instant =
        java.time.ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneId.of(zone)).toInstant()
}
