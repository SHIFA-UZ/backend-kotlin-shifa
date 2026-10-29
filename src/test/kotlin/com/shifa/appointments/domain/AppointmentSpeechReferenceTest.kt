package com.shifa.appointments.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class AppointmentSpeechReferenceTest {
    @Test
    fun `uzbek reschedule keeps source time dest day and dest time`() {
        val parsed = AppointmentSpeechReference.parse(
            "Tushunarli. Demak, 10:00 dagi Franko Herselman bilan qilingan uchrashuvni ertaga ko'chiring. Ertaga 12:00 ga.",
            LocalDate.parse("2026-10-20"),
        )
        assertEquals("Franko Herselman", parsed.patientName)
        assertEquals(LocalTime.parse("10:00"), parsed.sourceTime)
        assertEquals(LocalDate.parse("2026-10-21"), parsed.destinationDate)
        assertEquals(LocalTime.parse("12:00"), parsed.destinationTime)
    }
}
