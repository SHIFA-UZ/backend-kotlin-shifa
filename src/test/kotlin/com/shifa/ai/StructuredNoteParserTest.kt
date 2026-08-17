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
}
