package com.shifa.service

import com.shifa.domain.ConsultationNote

/**
 * Builds appointment-documentation text: AI scribe first, then the doctor's own notes.
 */
object AppointmentDocumentationComposer {
    const val SOURCE_AI = "AI_DRAFT"
    const val SOURCE_MANUAL = "MANUAL"

    data class Headings(
        val scribeTitle: String,
        val doctorNotesTitle: String,
        val transcriptTitle: String,
    )

    fun headingsFor(language: String?): Headings {
        return when (language?.lowercase()?.take(2)) {
            "ru" -> Headings(
                scribeTitle = "AI-секретарь — протокол консультации",
                doctorNotesTitle = "Заметки врача",
                transcriptTitle = "Транскрипт",
            )
            "en" -> Headings(
                scribeTitle = "AI Scribe — consultation protocol",
                doctorNotesTitle = "Doctor notes",
                transcriptTitle = "Transcript",
            )
            else -> Headings(
                scribeTitle = "AI yozuvchi — konsultatsiya bayonnomasi",
                doctorNotesTitle = "Shifokor izohlari",
                transcriptTitle = "Transkripsiyasi",
            )
        }
    }

    fun displayText(note: ConsultationNote): String {
        val body = note.body?.trim().orEmpty()
        if (body.isNotEmpty()) return body
        return listOfNotNull(
            note.subjective?.takeIf { it.isNotBlank() },
            note.assessment?.takeIf { it.isNotBlank() },
            note.plan?.takeIf { it.isNotBlank() },
        ).joinToString("\n\n").trim()
    }

    fun compose(notes: List<ConsultationNote>, language: String?): String {
        val headings = headingsFor(language)
        val scribeNotes = notes.filter { it.source == SOURCE_AI }
        val doctorNotes = notes.filter { it.source != SOURCE_AI }
        val parts = mutableListOf<String>()
        if (scribeNotes.isNotEmpty()) {
            val scribeBody = scribeNotes.joinToString("\n\n") { displayText(it) }.trim()
            if (scribeBody.isNotEmpty()) {
                parts += "${headings.scribeTitle}\n\n$scribeBody"
            }
        }
        if (doctorNotes.isNotEmpty()) {
            val doctorBody = doctorNotes.joinToString("\n\n") { displayText(it) }.trim()
            if (doctorBody.isNotEmpty()) {
                parts += "${headings.doctorNotesTitle}\n\n$doctorBody"
            }
        }
        return parts.joinToString("\n\n").trim()
    }
}
