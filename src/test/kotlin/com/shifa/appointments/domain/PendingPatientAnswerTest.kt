package com.shifa.appointments.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PendingPatientAnswerTest {
    @Test
    fun `affirmative filler after a confirmation stem is a confirm not a new search`() {
        val result = PendingPatientAnswer.classify("Ha shunday.")
        assertEquals(PendingPatientAnswer.Kind.CONFIRM, result.kind)
        assertEquals(BookingTurnType.CONFIRM, result.turnType)
        assertNull(result.correctionName)
    }

    @Test
    fun `explicit no rejects the pending candidate`() {
        assertEquals(PendingPatientAnswer.Kind.REJECT, PendingPatientAnswer.classify("No.").kind)
        assertEquals(PendingPatientAnswer.Kind.REJECT, PendingPatientAnswer.classify("Yo'q").kind)
    }

    @Test
    fun `a different name is a correction`() {
        val result = PendingPatientAnswer.classify("Alice Smith")
        assertEquals(PendingPatientAnswer.Kind.CORRECT, result.kind)
        assertEquals("alice smith", result.correctionName)
    }

    @Test
    fun `affirmative answers in supported languages confirm the pending question`() {
        listOf("Ha.", "Ha, aynan shunday.", "To'g'ri.", "Yes, exactly.", "Ja, genau.", "Да, именно.")
            .forEach { phrase ->
                assertEquals(
                    PendingPatientAnswer.Kind.CONFIRM,
                    PendingPatientAnswer.classify(phrase).kind,
                    phrase,
                )
            }
    }

    @Test
    fun `a reschedule request is unrelated to the pending patient question`() {
        val result = PendingPatientAnswer.classify(
            "Tushunarli. Demak, 10:00 dagi Franko Herselman bilan qilingan uchrashuvni ertaga ko'chiring.",
        )
        assertEquals(PendingPatientAnswer.Kind.UNRELATED, result.kind)
    }
}
