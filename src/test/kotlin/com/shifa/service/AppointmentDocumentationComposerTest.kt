package com.shifa.service

import com.shifa.domain.ConsultationNote
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppointmentDocumentationComposerTest {
    @Test
    fun `scribe notes appear before doctor notes`() {
        val scribe = ConsultationNote(
            doctorId = 1,
            patientId = 2,
            appointmentId = 3,
            body = "SOAP protocol and transcript",
            source = "AI_DRAFT",
        )
        val doctor = ConsultationNote(
            doctorId = 1,
            patientId = 2,
            appointmentId = 3,
            body = "Follow up in 1 week",
            source = "MANUAL",
        )
        val composed = AppointmentDocumentationComposer.compose(listOf(doctor, scribe), "en")
        val scribeAt = composed.indexOf("SOAP protocol and transcript")
        val doctorAt = composed.indexOf("Follow up in 1 week")
        assertTrue(scribeAt >= 0 && doctorAt > scribeAt)
        assertTrue(composed.contains("AI Scribe"))
        assertTrue(composed.contains("Doctor notes"))
    }

    @Test
    fun `formatAsScribeNote appends transcript after soap`() {
        val formatted = MedicalSummaryService(com.shifa.config.OpenAiProperties()).formatAsScribeNote(
            MedicalSummaryService.ScribeSummaryResult(
                subjective = "Headache",
                assessment = "Tension",
                plan = "Rest",
                body = "Combined note",
            ),
            transcript = "Doctor: hello\nPatient: my head hurts",
        )
        assertTrue(formatted.indexOf("SUBJECTIVE:") < formatted.indexOf("TRANSCRIPT:"))
        assertTrue(formatted.contains("Doctor: hello"))
        assertEquals("Headache", StructuredNoteParserSafe.subjective(formatted))
    }
}

private object StructuredNoteParserSafe {
    fun subjective(text: String): String? = com.shifa.ai.StructuredNoteParser.parse(text).subjective
}
