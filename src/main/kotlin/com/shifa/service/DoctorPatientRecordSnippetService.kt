package com.shifa.service

import com.shifa.ai.ConsultationNoteAiSnapshot
import com.shifa.ai.Form0252AiSnapshot
import com.shifa.domain.ConsultationNote
import com.shifa.domain.PatientForm
import com.shifa.repo.ConsultationNoteRepository
import com.shifa.repo.PatientFormRepository
import org.springframework.stereotype.Service
import java.time.ZoneId

/**
 * Shared structured clinical snippets for [com.shifa.ai.PatientAiContextBuilder] (Ask Shifa AI)
 * and [PatientBriefingService] so chat and briefing stay aligned.
 */
@Service
class DoctorPatientRecordSnippetService(
    private val patientFormRepo: PatientFormRepository,
    private val consultationNoteRepo: ConsultationNoteRepository,
) {

    fun buildForm0252Snapshots(patientId: Long, maxForms: Int): List<Form0252AiSnapshot> {
        val take = maxForms.coerceAtLeast(1)
        return patientFormRepo
            .findByPatientIdAndTemplateIdOrderByDateDesc(patientId, "025-2")
            .take(take)
            .map { formToSnapshot(it) }
    }

    fun buildConsultationNoteSnapshots(
        doctorId: Long,
        patientId: Long,
        maxNotes: Int,
        maxCharsPerNote: Int,
        maxTotalChars: Int,
    ): List<ConsultationNoteAiSnapshot> {
        val capNotes = maxNotes.coerceAtLeast(1)
        val perNote = maxCharsPerNote.coerceAtLeast(256)
        val notes = consultationNoteRepo.findByDoctorIdAndPatientIdOrderByCreatedAtDesc(doctorId, patientId)
            .take(capNotes)
        val zone = ZoneId.systemDefault()
        var budget = maxTotalChars.coerceAtLeast(perNote)
        val out = ArrayList<ConsultationNoteAiSnapshot>(notes.size)
        for (n in notes) {
            if (budget <= 0) break
            val raw = combineConsultationNoteText(n).trim()
            if (raw.isEmpty()) continue
            val maxThis = minOf(perNote, budget)
            val excerpt = raw.take(maxThis)
            budget -= excerpt.length
            out += ConsultationNoteAiSnapshot(
                date = n.createdAt.atZone(zone).toLocalDate(),
                excerpt = excerpt,
                source = n.source,
            )
        }
        return out
    }

    companion object {
        /** Defaults aligned with Ask Shifa AI streaming context (compact). */
        const val AI_STREAM_MAX_0252_FORMS = 8
        const val AI_STREAM_MAX_CONSULTATION_NOTES = 12
        const val AI_STREAM_MAX_CHARS_PER_NOTE = 2_800
        const val AI_STREAM_MAX_TOTAL_NOTE_CHARS = 22_000

        /** Patient briefing: room for notes alongside PDF excerpts. */
        const val BRIEFING_MAX_0252_FORMS = 12
        const val BRIEFING_MAX_CONSULTATION_NOTES = 24
        const val BRIEFING_MAX_CHARS_PER_CONSULTATION_NOTE = 10_000
        const val BRIEFING_MAX_TOTAL_CONSULTATION_CHARS = 42_000

        /** Soft cap for formatted 025-2 block inside briefing context string. */
        const val BRIEFING_MAX_CHARS_FORM_0252_SECTION = 36_000

        /** PDF text budget after reserving structured sections (approximate). */
        const val BRIEFING_DOC_TEXT_SOFT_BUDGET = 52_000
    }

    private fun formToSnapshot(form: PatientForm): Form0252AiSnapshot {
        val chart = form.dentalChart ?: emptyMap()
        val dentalLines = chart.entries
            .filter { (_, v) -> v.isNotBlank() }
            .sortedWith(compareBy({ sortableToothKey(it.key) }, { it.key }))
            .map { (tooth, value) ->
                val v = value.trim().take(MAX_CHARS_DENTAL_VALUE)
                "Tooth $tooth: $v"
            }

        val narrativeLines = mutableListOf<String>()
        fun addLine(label: String, text: String?) {
            val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return
            narrativeLines += "$label: ${t.take(MAX_NARRATIVE_PER_FIELD)}"
        }
        addLine("Complaints", form.complaints)
        addLine("Visual checkup", form.visualCheckup)
        addLine("Oral cavity", form.oralCavityCondition)
        addLine("Occlusion", form.occlusion)
        val dx = form.diagnosisDisplay?.takeIf { it.isNotBlank() } ?: form.diagnosis
        addLine("Diagnosis", dx)
        addLine("X-ray / lab", form.xrayLabData)
        addLine("Treatment", form.treatment)
        addLine("Treatment result", form.treatmentResult)
        addLine("Recommendations", form.recommendations)
        addLine("Other illnesses", form.otherIllnesses)
        addLine("More details", form.moreDetails)

        val followLines = (form.followups ?: emptyList()).mapNotNull { m ->
            val d = m["date"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val findings = (m["clinicalFindings"] ?: "").trim().take(MAX_FOLLOWUP_CLINICAL)
            if (findings.isEmpty()) return@mapNotNull null
            val who = m["doctorName"]?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            "Follow-up $d$who: $findings"
        }

        return Form0252AiSnapshot(
            formDate = form.date,
            formNumber = form.formNumber,
            dentalChartLines = dentalLines,
            narrativeLines = narrativeLines,
            followUpLines = followLines,
        )
    }

    private fun combineConsultationNoteText(n: ConsultationNote): String {
        val body = n.body?.trim().orEmpty()
        if (body.isNotEmpty()) return body
        return listOfNotNull(
            n.subjective?.takeIf { it.isNotBlank() }?.let { "Subjective: ${it.trim()}" },
            n.assessment?.takeIf { it.isNotBlank() }?.let { "Assessment: ${it.trim()}" },
            n.plan?.takeIf { it.isNotBlank() }?.let { "Plan: ${it.trim()}" },
        ).joinToString("\n\n")
    }

    private fun sortableToothKey(key: String): Int =
        key.filter { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE
}

private const val MAX_CHARS_DENTAL_VALUE = 280
private const val MAX_NARRATIVE_PER_FIELD = 900
private const val MAX_FOLLOWUP_CLINICAL = 450
