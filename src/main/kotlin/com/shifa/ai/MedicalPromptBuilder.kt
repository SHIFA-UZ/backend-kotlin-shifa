package com.shifa.ai

import com.shifa.domain.DoctorProfile

object MedicalPromptBuilder {

    fun systemPrompt(
        doctor: DoctorProfile,
        language: OutputLanguage
    ): String = """
You are Shifa AI, a medical support assistant for doctors.

⚠️ IMPORTANT SAFETY RULES (MANDATORY):

1. You are NOT a doctor and do NOT replace professional medical judgment.
2. You must NOT:
   - Make medical diagnoses
   - Prescribe medications
   - Suggest dosages or treatment plans
3. You MAY:
   - Provide general medical information
   - Explain common symptoms in neutral terms
   - Suggest when to seek professional medical help

🚨 EMERGENCY ESCALATION RULE:
If the user's message mentions symptoms that could indicate a medical emergency 
(e.g. chest pain, stroke symptoms, severe bleeding, pregnancy complications, or children with concerning symptoms),
you MUST:
- Clearly state that this could be an emergency
- Advise seeking immediate medical care or emergency services
- Stop further speculative discussion

🎯 RESPONSE STYLE:
- Conservative, calm, and professional
- No alarming language unless escalation is required
- Use simple, clear explanations
- Avoid absolute statements

📚 KNOWLEDGE SOURCES:
- Always combine what you know from general, up‑to‑date medical knowledge and reputable public sources you were trained on
- When patient context is provided, prioritize it and clearly separate "From patient record" information from general medical background

📝 FORMATTING (IMPORTANT — MATCH PATIENT BRIEFING STYLE):
- Output must be **plain text only** — do NOT use Markdown (no **bold**, no bullet markers like "-", no numbered lists like "1.", no headings with "#")
- Use short paragraphs and simple, readable sentences
- When you need to list items, put each item on its own line starting with a simple word or phrase (not "- " or "* ")
- Keep answers concise and clinically focused; avoid long essays

👤 CONTEXT:
You are assisting a licensed doctor: 
Name: ${doctor.firstName} ${doctor.lastName}

Language: ${language.name}

Acknowledge uncertainty when appropriate.
Always prioritize patient safety.
""".trimIndent()

    fun userPrompt(
        patientContext: String,
        question: String
    ): String = """
Patient context:
$patientContext

User question:
$question
""".trimIndent()

//delivering optimized patient context
fun patientContextPrompt(ctx: PatientAiContext): String {
    return buildString {
        append("Patient context (read-only, from this doctor's records):\n")

        ctx.age?.let { append("- Age: $it\n") }
        ctx.language?.let { append("- Language: $it\n") }

        if (ctx.documentPdfExcerpts.isNotEmpty()) {
            append(
                "- Patient documents with extracted PDF text (excerpts may be truncated; non-PDF or unreadable files omitted):\n"
            )
            ctx.documentPdfExcerpts.forEach { doc ->
                append("  • Document id ${doc.documentId}: ${doc.title} (${doc.date})\n")
                doc.excerpt.lineSequence().forEach { ln ->
                    append("    ").append(ln).append('\n')
                }
            }
        }

        if (ctx.documentSummaries.isNotEmpty()) {
            append("- Other documents this doctor can view (metadata only; PDF text not included above):\n")
            ctx.documentSummaries.forEach {
                append("  • Document id ${it.documentId}: ${it.title} (${it.date})\n")
            }
        }

        if (ctx.appointmentSummaries.isNotEmpty()) {
            append("- Recent appointments with this doctor (date and reason):\n")
            ctx.appointmentSummaries.forEach { appt ->
                val reasonPart = appt.reason?.takeIf { it.isNotBlank() }?.let { reason -> ": $reason" } ?: ""
                append("  • ${appt.date}$reasonPart\n")
            }
        }

        if (ctx.form0252Snapshots.isNotEmpty()) {
            append(
                "- Saved form 025-2 records for this patient (use for factual recall: tooth chart + visit text; " +
                    "multiple forms may exist over time). Tooth numbers use ISO 3950 / FDI (e.g. 22 is upper left lateral incisor). " +
                    "When the doctor says only a tooth number, assume FDI unless they specify another system:\n"
            )
            ctx.form0252Snapshots.forEach { snap ->
                val numPart = snap.formNumber?.let { n -> " (form #$n)" } ?: ""
                append("  • Form date ${snap.formDate}$numPart\n")
                if (snap.dentalChartLines.isNotEmpty()) {
                    append("    Dental chart (tooth-specific entries only; empty teeth omitted):\n")
                    snap.dentalChartLines.forEach { line ->
                        append("      ").append(line).append('\n')
                    }
                }
                if (snap.narrativeLines.isNotEmpty()) {
                    append("    Clinical / administrative text from the form:\n")
                    snap.narrativeLines.forEach { line ->
                        append("      ").append(line).append('\n')
                    }
                }
                if (snap.followUpLines.isNotEmpty()) {
                    append("    Follow-up visit rows on this form:\n")
                    snap.followUpLines.forEach { line ->
                        append("      ").append(line).append('\n')
                    }
                }
            }
        }

        if (ctx.consultationNoteSnapshots.isNotEmpty()) {
            append(
                "- Consultation notes from visits with you (excerpts; may be truncated). " +
                    "Source tag when present: MANUAL = typed by staff, AI_DRAFT = saved from Shifa AI.\n"
            )
            ctx.consultationNoteSnapshots.forEach { note ->
                val src = note.source?.takeIf { it.isNotBlank() }?.let { s -> " [$s]" } ?: ""
                append("  • ${note.date}$src\n")
                note.excerpt.lineSequence().forEach { ln ->
                    append("    ").append(ln).append('\n')
                }
            }
        }

        append("\nUse this only for contextual awareness. Do NOT diagnose or change treatment plans.")
    }
    }

    fun clinicalRagContextPrompt(hits: List<com.shifa.clinical.ClinicalRagSearchHit>): String {
        return buildString {
            append(
                "Semantically retrieved passages from this patient's indexed clinical records " +
                    "(form 025-2 dental charts and narratives, consultation notes, appointment dental visit documentation). " +
                    "Distance is cosine distance (lower means a closer match to the doctor's latest question). " +
                    "Prefer these passages for factual recall about specific teeth or visits; if not documented, say so.\n\n"
            )
            hits.forEachIndexed { idx, h ->
                append("Passage ")
                append(idx + 1)
                append(" source=")
                append(h.sourceType)
                append(" chunkDbId=")
                append(h.id)
                append(" distance=")
                append(String.format("%.4f", h.distance))
                append('\n')
                append(h.contentText.trim().replace("\r\n", "\n").take(1400))
                append("\n\n")
            }
        }
    }
}
