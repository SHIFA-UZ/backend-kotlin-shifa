package com.shifa.service

import com.shifa.ai.ConsultationNoteAiSnapshot
import com.shifa.ai.Form0252AiSnapshot
import com.shifa.domain.Appointment
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.PatientProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Max documents to include in briefing context (iteration order). */
const val BRIEFING_MAX_DOCUMENTS = 20

/** Max characters per single PDF excerpt while assembling briefing context. */
const val BRIEFING_MAX_CHARS_PER_DOC = 12_000

/** Max appointments to include in briefing context. */
const val BRIEFING_MAX_APPOINTMENTS = 30

data class DocumentWithContent(
    val title: String,
    val date: LocalDate,
    val extractedText: String
)

data class PatientBriefingResult(
    val briefing: String,
    val documentCount: Int,
    val appointmentCount: Int
)

/**
 * Builds doctor-scoped context (documents, structured 025-2, consultation notes, appointments)
 * then calls OpenAI for a patient briefing.
 */
@Service
class PatientBriefingService(
    private val patientDocumentService: PatientDocumentService,
    private val documentStorage: PatientDocumentStorageService,
    private val pdfExtraction: PdfTextExtractionService,
    private val patientRepo: PatientProfileRepository,
    private val appointmentRepo: AppointmentRepository,
    private val openAi: OpenAiResponsesService,
    private val clinicalSnippets: DoctorPatientRecordSnippetService,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * Generate a concise patient briefing from documents the doctor can access,
     * structured 025-2 records, consultation notes with this doctor, and appointments.
     */
    fun generateBriefing(patientId: Long, doctorId: Long, outputLanguage: String = "en"): PatientBriefingResult {
        val patient = patientRepo.findById(patientId)
            .orElseThrow { IllegalArgumentException("Patient not found") }

        val documentsWithAccess = patientDocumentService.listDocumentsWithAccess(patientId, doctorId)
        val appointmentsWithDoctor = appointmentRepo.findByPatientIdAndDoctorIdOrderByStartAtDesc(patientId, doctorId)

        val formSnapshots = clinicalSnippets.buildForm0252Snapshots(
            patientId,
            DoctorPatientRecordSnippetService.BRIEFING_MAX_0252_FORMS
        )
        val consultationSnapshots = clinicalSnippets.buildConsultationNoteSnapshots(
            doctorId = doctorId,
            patientId = patientId,
            maxNotes = DoctorPatientRecordSnippetService.BRIEFING_MAX_CONSULTATION_NOTES,
            maxCharsPerNote = DoctorPatientRecordSnippetService.BRIEFING_MAX_CHARS_PER_CONSULTATION_NOTE,
            maxTotalChars = DoctorPatientRecordSnippetService.BRIEFING_MAX_TOTAL_CONSULTATION_CHARS,
        )

        val docsWithContent = mutableListOf<DocumentWithContent>()
        var totalChars = 0
        val docBudget = DoctorPatientRecordSnippetService.BRIEFING_DOC_TEXT_SOFT_BUDGET
        for (doc in documentsWithAccess.take(BRIEFING_MAX_DOCUMENTS)) {
            if (totalChars >= docBudget) break
            val resource = documentStorage.getFileResource(doc.filePath) ?: continue
            val rawText = pdfExtraction.extractText(resource)
            val room = (docBudget - totalChars).coerceAtLeast(0)
            if (room <= 0) break
            val text = rawText.take(minOf(BRIEFING_MAX_CHARS_PER_DOC, room))
            if (text.isBlank()) continue
            docsWithContent.add(
                DocumentWithContent(
                    title = doc.title,
                    date = doc.date,
                    extractedText = text
                )
            )
            totalChars += text.length
        }

        val appointmentsForContext = appointmentsWithDoctor.take(BRIEFING_MAX_APPOINTMENTS)

        if (docsWithContent.isEmpty() &&
            appointmentsForContext.isEmpty() &&
            formSnapshots.isEmpty() &&
            consultationSnapshots.isEmpty()
        ) {
            throw IllegalStateException(
                "No documents with extractable text, no appointments, no saved form 025-2 data, " +
                    "and no consultation notes for this doctor and patient. Add clinical content or an appointment first."
            )
        }

        val age = patient.birthDate?.let { Period.between(it, LocalDate.now()).years }
        val context = buildContext(
            patientName = patient.fullName,
            age = age,
            language = patient.language,
            docs = docsWithContent,
            appointments = appointmentsForContext,
            formSnapshots = formSnapshots,
            consultationSnapshots = consultationSnapshots,
        )
        val briefing = openAi.completeBriefing(context, outputLanguage)
        return PatientBriefingResult(
            briefing = briefing,
            documentCount = docsWithContent.size,
            appointmentCount = appointmentsForContext.size
        )
    }

    private fun buildContext(
        patientName: String,
        age: Int?,
        language: String?,
        docs: List<DocumentWithContent>,
        appointments: List<Appointment>,
        formSnapshots: List<Form0252AiSnapshot>,
        consultationSnapshots: List<ConsultationNoteAiSnapshot>,
    ): String {
        val sb = StringBuilder()
        sb.append("Patient: $patientName\n")
        age?.let { sb.append("Age: $it\n") }
        language?.let { sb.append("Preferred language: $it\n") }

        if (appointments.isNotEmpty()) {
            sb.append("\n--- Appointments with this doctor (date, reason, status, location) ---\n\n")
            for (a in appointments) {
                val dateStr = dateFormatter.format(a.startAt.atZone(ZoneId.systemDefault()).toLocalDate())
                sb.append("Appointment: $dateStr")
                a.reason?.takeIf { it.isNotBlank() }?.let { sb.append(" | Reason: $it") }
                sb.append(" | Status: ${a.status.name} | Location: ${a.location}\n")
            }
            sb.append("\n")
        }

        if (formSnapshots.isNotEmpty()) {
            val section = buildForm0252Section(formSnapshots)
            val capped = section.take(DoctorPatientRecordSnippetService.BRIEFING_MAX_CHARS_FORM_0252_SECTION)
            sb.append("\n--- Saved form 025-2 (structured dental chart + narrative fields; newest forms listed first) ---\n\n")
            sb.append(capped)
            if (section.length > capped.length) {
                sb.append("\n\n[025-2 section truncated for length]\n")
                log.debug("Briefing 025-2 section truncated from {} chars", section.length)
            }
            sb.append("\n")
        }

        if (consultationSnapshots.isNotEmpty()) {
            sb.append("\n--- Consultation notes (this doctor and patient; excerpts may be truncated) ---\n")
            sb.append("Source: MANUAL = typed in EMR, AI_DRAFT = saved from Shifa AI assistant.\n\n")
            for (note in consultationSnapshots) {
                val src = note.source?.takeIf { it.isNotBlank() }?.let { s -> " [$s]" } ?: ""
                sb.append("Note date ${note.date}$src\n")
                sb.append(note.excerpt)
                sb.append("\n\n")
            }
        }

        if (docs.isNotEmpty()) {
            sb.append("\n--- Documents (PDF extracted text; may be multilingual: Uzbek, Russian, English, etc.) ---\n\n")
            for (d in docs) {
                sb.append("Document: ${d.title} (Date: ${d.date})\n")
                sb.append(d.extractedText)
                sb.append("\n\n")
            }
        }
        return sb.toString()
    }

    private fun buildForm0252Section(snaps: List<Form0252AiSnapshot>): String =
        buildString {
            snaps.forEach { snap ->
                val numPart = snap.formNumber?.let { n -> " (form #$n)" } ?: ""
                append("Form date ${snap.formDate}$numPart\n")
                if (snap.dentalChartLines.isNotEmpty()) {
                    append("Dental chart:\n")
                    snap.dentalChartLines.forEach { line ->
                        append("  ").append(line).append('\n')
                    }
                }
                if (snap.narrativeLines.isNotEmpty()) {
                    append("Clinical / administrative text:\n")
                    snap.narrativeLines.forEach { line ->
                        append("  ").append(line).append('\n')
                    }
                }
                if (snap.followUpLines.isNotEmpty()) {
                    append("Follow-up rows:\n")
                    snap.followUpLines.forEach { line ->
                        append("  ").append(line).append('\n')
                    }
                }
                append('\n')
            }
        }
}
