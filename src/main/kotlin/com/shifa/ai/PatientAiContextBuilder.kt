package com.shifa.ai

import com.shifa.repo.AppointmentRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.service.DoctorPatientRecordSnippetService
import com.shifa.service.PatientDocumentService
import com.shifa.service.PatientDocumentStorageService
import com.shifa.service.PdfTextExtractionService
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

@Component
class PatientAiContextBuilder(
    private val patientRepo: PatientProfileRepository,
    private val patientDocumentService: PatientDocumentService,
    private val appointmentRepo: AppointmentRepository,
    private val clinicalSnippets: DoctorPatientRecordSnippetService,
    private val documentStorage: PatientDocumentStorageService,
    private val pdfExtraction: PdfTextExtractionService,
) {

    companion object {
        /** PDFs to pull text from per Ask Shifa request (doctor-accessible patient uploads). */
        private const val AI_STREAM_MAX_PDF_DOCUMENTS = 6
        private const val AI_STREAM_MAX_CHARS_PER_PDF = 5_000
        private const val AI_STREAM_MAX_TOTAL_PDF_CHARS = 22_000
        /** Cap metadata-only rows when many documents exist beyond excerpt budget. */
        private const val AI_STREAM_MAX_DOCUMENT_SUMMARIES = 18
    }

    /**
     * Build a richer AI context for a given patient **and doctor**.
     *
     * - Includes patient age and preferred language
     * - Includes PDF excerpts from documents this doctor can view (bounded size)
     * - Metadata for remaining documents (title, date, id)
     * - Recent appointments with this doctor
     * - Recent **025-2** snapshots and **consultation notes** (via [DoctorPatientRecordSnippetService])
     */
    fun build(patientId: Long, doctorId: Long): PatientAiContext {
        val patient = patientRepo.findById(patientId)
            .orElseThrow { IllegalArgumentException("Patient not found") }

        val age = patient.birthDate?.let {
            Period.between(it, LocalDate.now()).years
        }

        val allDocs = patientDocumentService.listDocumentsWithAccess(patientId, doctorId)

        val pdfExcerpts = mutableListOf<DocumentPdfExcerpt>()
        var pdfBudget = AI_STREAM_MAX_TOTAL_PDF_CHARS
        var pdfCount = 0
        val excerptIds = mutableSetOf<Long>()
        for (doc in allDocs) {
            if (pdfCount >= AI_STREAM_MAX_PDF_DOCUMENTS || pdfBudget <= 0) break
            val docId = doc.id ?: continue
            val resource = documentStorage.getFileResource(doc.filePath) ?: continue
            val rawText = pdfExtraction.extractText(resource).trim()
            if (rawText.isBlank()) continue
            val takeLen = minOf(AI_STREAM_MAX_CHARS_PER_PDF, pdfBudget)
            val excerpt = rawText.take(takeLen)
            pdfExcerpts += DocumentPdfExcerpt(
                documentId = docId,
                title = doc.title,
                date = doc.date,
                excerpt = excerpt,
            )
            excerptIds += docId
            pdfBudget -= excerpt.length
            pdfCount++
        }

        val documentSummaries = allDocs
            .asSequence()
            .mapNotNull { d -> d.id?.let { id -> Triple(id, d.title, d.date) } }
            .filter { (id, _, _) -> id !in excerptIds }
            .take(AI_STREAM_MAX_DOCUMENT_SUMMARIES)
            .map { (id, title, date) -> DocumentSummary(documentId = id, title = title, date = date) }
            .toList()

        val appointmentSummaries = appointmentRepo
            .findByPatientIdAndDoctorIdOrderByStartAtDesc(patientId, doctorId)
            .take(10)
            .map { appt ->
                AppointmentSummary(
                    date = appt.startAt.atZone(ZoneId.systemDefault()).toLocalDate(),
                    reason = appt.reason
                )
            }

        val form0252Snapshots = clinicalSnippets.buildForm0252Snapshots(
            patientId,
            DoctorPatientRecordSnippetService.AI_STREAM_MAX_0252_FORMS
        )
        val consultationNoteSnapshots = clinicalSnippets.buildConsultationNoteSnapshots(
            doctorId = doctorId,
            patientId = patientId,
            maxNotes = DoctorPatientRecordSnippetService.AI_STREAM_MAX_CONSULTATION_NOTES,
            maxCharsPerNote = DoctorPatientRecordSnippetService.AI_STREAM_MAX_CHARS_PER_NOTE,
            maxTotalChars = DoctorPatientRecordSnippetService.AI_STREAM_MAX_TOTAL_NOTE_CHARS,
        )

        return PatientAiContext(
            patientId = patient.id!!,
            age = age,
            language = patient.language,
            documentSummaries = documentSummaries,
            appointmentSummaries = appointmentSummaries,
            documentPdfExcerpts = pdfExcerpts,
            form0252Snapshots = form0252Snapshots,
            consultationNoteSnapshots = consultationNoteSnapshots,
        )
    }
}
