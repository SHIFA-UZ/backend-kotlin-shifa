package com.shifa.appointments.application

import com.shifa.ai.application.MedicalSummaryService

import com.shifa.appointments.domain.ConsultationNote
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
        val formatted = MedicalSummaryService(com.shifa.shared.config.OpenAiProperties()).formatAsScribeNote(
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

    @Test
    fun `formatAsScribeNote labels unspoken AI advice separately`() {
        val formatted = MedicalSummaryService(com.shifa.shared.config.OpenAiProperties()).formatAsScribeNote(
            MedicalSummaryService.ScribeSummaryResult(
                subjective = "Abdominal pain",
                assessment = null,
                plan = null,
                body = "",
                aiRecommendations = "[AI recommendation] Consider pantoprazole if gastritis is confirmed",
            ),
            transcript = "Patient: qorin og'riydi",
        )
        assertTrue(formatted.contains("AI RECOMMENDATIONS (not spoken in the visit — Shifa AI):"))
        assertTrue(formatted.contains("[AI recommendation]"))
        val parsed = com.shifa.ai.domain.StructuredNoteParser.parse(formatted)
        assertEquals("Abdominal pain", parsed.subjective)
        assertEquals(null, parsed.plan)
    }
}

private object StructuredNoteParserSafe {
    fun subjective(text: String): String? = com.shifa.ai.domain.StructuredNoteParser.parse(text).subjective
}
