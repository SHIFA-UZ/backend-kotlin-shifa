package com.shifa.appointments.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MorningVisitIntentTest {
    @Test
    fun `uzbek dictation is a note and the clinical text is kept`() {
        val text =
            "Franko Xerselman bilan bo'ladigan 10:00 dagi qabulga quyidagi qaydlarni yozib bor: Franko Xerselman yuragida shikoyat bilan yonimga keldi."
        assertTrue(MorningVisitIntent.Action.NOTE in MorningVisitIntent.explicit(text))
        assertFalse(MorningVisitIntent.Action.OPEN in MorningVisitIntent.explicit(text))
        assertEquals(
            "Franko Xerselman yuragida shikoyat bilan yonimga keldi.",
            MorningVisitIntent.noteBody(text),
        )
    }

    @Test
    fun `showing today's schedule is not a note`() {
        assertTrue(MorningVisitIntent.explicit("menga bugungi qabullarimni ko'rsat.").isEmpty())
    }
}
