package com.shifa.intelligence.appointment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ClinicCalendarDayTest {
    private val today = LocalDate.parse("2026-10-19")

    @Test
    fun `day and month with no year uses the clock year`() {
        assertEquals(LocalDate.parse("2026-10-23"), ClinicCalendarDay.parse("23 October", today))
        assertEquals(LocalDate.parse("2026-10-23"), ClinicCalendarDay.parse("23 of october", today))
        assertEquals(LocalDate.parse("2026-10-23"), ClinicCalendarDay.parse("23rd of October", today))
        assertEquals(LocalDate.parse("2026-10-23"), ClinicCalendarDay.parse("23-oktabr", today))
        assertEquals(LocalDate.parse("2026-10-23"), ClinicCalendarDay.parse("23-oktabrdagi qabul", today))
        assertEquals(LocalDate.parse("2026-10-23"), ClinicCalendarDay.parse("October 23", today))
        assertEquals(LocalDate.parse("2026-10-15"), ClinicCalendarDay.parse("15 oktyabr", today))
    }

    @Test
    fun `stale ISO year is rewritten to the clock year`() {
        assertEquals(LocalDate.parse("2026-10-23"), ClinicCalendarDay.parse("2024-10-23", today))
        assertEquals(LocalDate.parse("2026-10-23"), ClinicCalendarDay.alignYear(LocalDate.parse("2024-10-23"), today))
        assertEquals(LocalDate.parse("2026-10-23"), ClinicCalendarDay.parse("23 October 2024", today))
    }

    @Test
    fun `current or future year is kept`() {
        assertEquals(LocalDate.parse("2026-06-01"), ClinicCalendarDay.parse("2026-06-01", today))
        assertEquals(LocalDate.parse("2027-03-01"), ClinicCalendarDay.parse("2027-03-01", today))
    }
}
