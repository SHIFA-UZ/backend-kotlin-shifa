package com.shifa.service

import com.shifa.domain.Appointment
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.PatientProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Max documents to include in briefing context. */
const val BRIEFING_MAX_DOCUMENTS = 20

/** Max characters per document excerpt (to stay within token limits). */
const val BRIEFING_MAX_CHARS_PER_DOC = 12_000

/** Max total characters for all document content combined. */
const val BRIEFING_MAX_TOTAL_CHARS = 80_000

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
 * Builds doctor-scoped document list and appointments (with this doctor only), then calls OpenAI
 * to produce a short patient briefing. Only documents the doctor can view and only appointments
 * with this doctor are included.
 */
@Service
class PatientBriefingService(
    private val patientDocumentService: PatientDocumentService,
    private val documentStorage: PatientDocumentStorageService,
    private val pdfExtraction: PdfTextExtractionService,
    private val patientRepo: PatientProfileRepository,
    private val appointmentRepo: AppointmentRepository,
    private val openAi: OpenAiResponsesService
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * Generate a concise patient briefing from documents the doctor can access and
     * appointments with this doctor. Requires at least one of: accessible document content, or appointments.
     */
    fun generateBriefing(patientId: Long, doctorId: Long, outputLanguage: String = "en"): PatientBriefingResult {
        val patient = patientRepo.findById(patientId)
            .orElseThrow { IllegalArgumentException("Patient not found") }

        val documentsWithAccess = patientDocumentService.listDocumentsWithAccess(patientId, doctorId)
        val appointmentsWithDoctor = appointmentRepo.findByPatientIdAndDoctorIdOrderByStartAtDesc(patientId, doctorId)

        val docsWithContent = mutableListOf<DocumentWithContent>()
        var totalChars = 0
        for (doc in documentsWithAccess.take(BRIEFING_MAX_DOCUMENTS)) {
            if (totalChars >= BRIEFING_MAX_TOTAL_CHARS) break
            val resource = documentStorage.getFileResource(doc.filePath) ?: continue
            val rawText = pdfExtraction.extractText(resource)
            val text = rawText.take(BRIEFING_MAX_CHARS_PER_DOC)
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

        if (docsWithContent.isEmpty() && appointmentsForContext.isEmpty()) {
            throw IllegalStateException(
                "No documents with access and no appointments with you for this patient. " +
                "Request access to patient documents or create an appointment first."
            )
        }

        val age = patient.birthDate?.let { Period.between(it, LocalDate.now()).years }
        val context = buildContext(
            patientName = patient.fullName,
            age = age,
            language = patient.language,
            docs = docsWithContent,
            appointments = appointmentsForContext
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
        appointments: List<Appointment>
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

        if (docs.isNotEmpty()) {
            sb.append("\n--- Documents (may be in different languages: Uzbek, Russian, English, etc.) ---\n\n")
            for (d in docs) {
                sb.append("Document: ${d.title} (Date: ${d.date})\n")
                sb.append(d.extractedText)
                sb.append("\n\n")
            }
        }
        return sb.toString()
    }
}
