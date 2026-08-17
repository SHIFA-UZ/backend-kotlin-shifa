package com.shifa.ai

import com.shifa.domain.ConsultationNote
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ConsultationNoteTextTest {
    @Test
    fun `stripTranscript drops raw dialogue after TRANSCRIPT header`() {
        val raw = """
            SUBJECTIVE:
            Tooth pain

            PLAN:
            Filling on 14

            TRANSCRIPT:
            Doctor: where does it hurt?
            Patient: upper right.
        """.trimIndent()
        val stripped = ConsultationNoteText.stripTranscript(raw)
        assertTrue(stripped.contains("Tooth pain"))
        assertTrue(stripped.contains("Filling on 14"))
        assertFalse(stripped.contains("where does it hurt"))
    }

    @Test
    fun `excerptForAi prefers SOAP fields over body with transcript`() {
        val note = ConsultationNote(
            doctorId = 1,
            patientId = 2,
            appointmentId = 3,
            subjective = "Pain on chewing",
            assessment = "Caries 14",
            plan = "Restoration",
            body = "SUBJECTIVE:\nPain\n\nTRANSCRIPT:\nlong dialogue",
            source = "AI_DRAFT",
            createdAt = Instant.parse("2026-08-11T10:00:00Z"),
        )
        val excerpt = ConsultationNoteText.excerptForAi(note)
        assertEquals(ConsultationNoteText.KIND_VIDEO_SCRIBE, ConsultationNoteText.kind(note))
        assertTrue(excerpt.contains("Pain on chewing"))
        assertFalse(excerpt.contains("long dialogue"))
    }

    @Test
    fun `manual notes stay manual`() {
        val note = ConsultationNote(
            doctorId = 1,
            patientId = 2,
            body = "Follow up in 1 week",
            source = "MANUAL",
        )
        assertEquals(ConsultationNoteText.KIND_MANUAL, ConsultationNoteText.kind(note))
        assertEquals("Follow up in 1 week", ConsultationNoteText.excerptForAi(note))
    }
}
