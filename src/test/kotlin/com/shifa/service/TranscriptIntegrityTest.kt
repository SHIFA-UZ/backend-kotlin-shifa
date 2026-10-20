package com.shifa.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TranscriptIntegrityTest {
    private val full = "Sentence one. Sentence two. Sentence three. Sentence four."

    @Test
    fun `a shorter cleanup is rejected and the received transcript is kept`() {
        assertEquals(full, TranscriptIntegrity.acceptCleanup(full, "Sentence one."))
    }

    @Test
    fun `an empty cleanup is rejected`() {
        assertEquals(full, TranscriptIntegrity.acceptCleanup(full, "  "))
    }

    @Test
    fun `a same-length correction is accepted`() {
        val corrected = full.replace("one", "1ne")
        assertEquals(corrected, TranscriptIntegrity.acceptCleanup(full, corrected))
    }
}
