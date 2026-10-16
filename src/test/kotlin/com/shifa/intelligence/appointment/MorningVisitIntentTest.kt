package com.shifa.intelligence.appointment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MorningVisitIntentTest {
    @Test
    fun `open start complete note and signature stay separate`() {
        assertEquals(setOf(MorningVisitIntent.Action.OPEN), MorningVisitIntent.explicit("Open my next appointment."))
        assertEquals(setOf(MorningVisitIntent.Action.START), MorningVisitIntent.explicit("Start my next appointment."))
        assertEquals(setOf(MorningVisitIntent.Action.COMPLETE), MorningVisitIntent.explicit("Complete this appointment."))
        assertEquals(setOf(MorningVisitIntent.Action.NOTE), MorningVisitIntent.explicit("Save these consultation notes."))
        assertEquals(
            setOf(MorningVisitIntent.Action.SIGNATURE),
            MorningVisitIntent.explicit("Request the patient's signature."),
        )
    }

    @Test
    fun `dictation does not complete or request a signature`() {
        val nudge = MorningVisitIntent.nudge("The patient reports a mild headache. Save these consultation notes.")
        assertTrue(nudge!!.contains("append_consultation_draft"))
        assertTrue(nudge.contains("do not complete"))
        assertTrue(nudge.contains("do not request a signature"))
        assertEquals(
            setOf(MorningVisitIntent.Action.NOTE),
            MorningVisitIntent.explicit("Complete the consultation notes."),
        )
    }

    @Test
    fun `an unspecified wrap up asks instead of choosing`() {
        val nudge = MorningVisitIntent.nudge("Let's wrap this up.")
        assertTrue(nudge!!.contains("AMBIGUOUS"))
        assertNull(MorningVisitIntent.nudge("How many appointments yesterday?"))
    }
}
