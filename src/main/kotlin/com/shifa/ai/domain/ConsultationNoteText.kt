package com.shifa.ai.domain

import com.shifa.appointments.domain.ConsultationNote

/**
 * Shared helpers so briefing / copilot get clinical protocol from scribe notes
 * without the raw visit transcript.
 */
object ConsultationNoteText {
    const val KIND_MANUAL = "MANUAL"
    const val KIND_AI_DRAFT = "AI_DRAFT"
    const val KIND_VIDEO_SCRIBE = "VIDEO_SCRIBE"

    private val transcriptHeader = Regex("(?im)^TRANSCRIPT:\\s*")

    fun kind(note: ConsultationNote): String {
        if (!note.source.equals("AI_DRAFT", ignoreCase = true)) return KIND_MANUAL
        val haystack = listOfNotNull(note.body, note.subjective, note.assessment, note.plan)
            .joinToString("\n")
        if (transcriptHeader.containsMatchIn(haystack) || looksLikeScribeSoap(haystack)) {
            return KIND_VIDEO_SCRIBE
        }
        return KIND_AI_DRAFT
    }

    fun stripTranscript(text: String): String {
        val match = transcriptHeader.find(text) ?: return text.trim()
        return text.substring(0, match.range.first).trim()
    }

    fun excerptForAi(note: ConsultationNote): String {
        val soap = listOfNotNull(
            note.subjective?.takeIf { it.isNotBlank() }?.let { "SUBJECTIVE:\n${it.trim()}" },
            note.assessment?.takeIf { it.isNotBlank() }?.let { "ASSESSMENT:\n${it.trim()}" },
            note.plan?.takeIf { it.isNotBlank() }?.let { "PLAN:\n${it.trim()}" },
        ).joinToString("\n\n")
        if (soap.isNotBlank()) return soap
        val body = note.body?.trim().orEmpty()
        if (body.isNotEmpty()) return stripTranscript(body)
        return ""
    }

    private fun looksLikeScribeSoap(text: String): Boolean {
        val upper = text.uppercase()
        val hasSubjective = upper.contains("SUBJECTIVE:")
        val hasAssessment = upper.contains("ASSESSMENT:")
        val hasPlan = upper.contains("PLAN:")
        return hasSubjective && (hasAssessment || hasPlan)
    }
}
