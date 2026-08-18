package com.shifa.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StructuredNoteParserTest {
    @Test
    fun `transcript section is not merged into plan`() {
        val parsed = StructuredNoteParser.parse(
            """
            SUBJECTIVE:
            Cough for 3 days

            PLAN:
            Rest and fluids

            TRANSCRIPT:
            Doctor: how are you?
            Patient: I have a cough.
            """.trimIndent()
        )
        assertEquals("Cough for 3 days", parsed.subjective)
        assertEquals("Rest and fluids", parsed.plan)
        assertNull(parsed.body)
    }

    @Test
    fun `AI recommendations are not merged into plan`() {
        val parsed = StructuredNoteParser.parse(
            """
            SUBJECTIVE:
            Abdominal pain after fatty food

            PLAN:
            Continue current medicines

            AI RECOMMENDATIONS (not spoken in the visit — Shifa AI):
            [AI recommendation] Consider a pediatrician referral

            TRANSCRIPT:
            Patient: my stomach hurts
            """.trimIndent()
        )
        assertEquals("Abdominal pain after fatty food", parsed.subjective)
        assertEquals("Continue current medicines", parsed.plan)
        assertNull(parsed.body)
    }
}
