package com.shifa.service

import com.shifa.domain.PatientProfile
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.PatientDocumentRepository
import com.shifa.repo.PatientFormRepository
import org.springframework.stereotype.Service
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Service
class PatientCopilotContextService(
    private val patientDocuments: PatientDocumentRepository,
    private val patientForms: PatientFormRepository,
    private val appointments: AppointmentRepository,
    private val documentStorage: PatientDocumentStorageService,
    private val pdfTextExtraction: PdfTextExtractionService
) {
    private val dtFmt = DateTimeFormatter.ISO_LOCAL_DATE
    enum class Intent { DOCUMENTS, SYMPTOMS, APPOINTMENTS, GENERAL }

    data class StructuredMemory(
        val symptoms: List<String> = emptyList(),
        val suspectedConditions: List<String> = emptyList(),
        val selectedDoctorIds: List<Long> = emptyList(),
        val bookingDecisions: List<String> = emptyList()
    )

    fun detectIntent(latestUserText: String): Intent {
        val t = latestUserText.lowercase()
        if (listOf("document", "lab", "report", "pdf", "scan", "analysis", "result").any { t.contains(it) }) {
            return Intent.DOCUMENTS
        }
        if (listOf("book", "appointment", "schedule", "slot", "time", "date").any { t.contains(it) }) {
            return Intent.APPOINTMENTS
        }
        if (listOf("pain", "fever", "cough", "symptom", "headache", "nausea", "rash", "chest").any { t.contains(it) }) {
            return Intent.SYMPTOMS
        }
        return Intent.GENERAL
    }

    /**
     * Build richer patient-owned context for patient-facing copilot.
     * Includes appointments history, diagnoses/conditions labels (if present in forms/profile),
     * medications text if present in forms, and document metadata.
     * When [includeDocumentSnippets] is true, includes short extracted snippets from the latest PDFs.
     */
    fun buildContextText(patient: PatientProfile, includeDocumentSnippets: Boolean): String {
        val patientId = patient.id ?: return ""
        val docs = patientDocuments.listForPatient(patientId)
            .filter { !it.isChatAttachment && it.filePath.isNotBlank() }
            .take(8)
        val forms = patientForms.findByPatientIdOrderByDateDesc(patientId).take(5)
        val appts = appointments.findByPatientId(patientId).take(12)

        val sb = StringBuilder()
        sb.append("Patient-owned context (facts only; never invent missing values):\n")
        patient.chronicDisease?.takeIf { it.isNotBlank() }?.let {
            sb.append("- Chronic conditions label on profile: ").append(it.trim()).append('\n')
        }

        if (forms.isNotEmpty()) {
            sb.append("- Latest intake forms:\n")
            for (f in forms) {
                val date = f.date.format(dtFmt)
                sb.append("  • ").append(date)
                f.diagnosisDisplay?.takeIf { it.isNotBlank() }?.let { sb.append(" | diagnosis: ").append(it.trim()) }
                    ?: f.diagnosis?.takeIf { it.isNotBlank() }?.let { sb.append(" | diagnosis: ").append(it.trim()) }
                f.treatment?.takeIf { it.isNotBlank() }?.let { sb.append(" | treatment/meds: ").append(it.trim()) }
                f.complaints?.takeIf { it.isNotBlank() }?.let { sb.append(" | complaint: ").append(it.trim()) }
                sb.append('\n')
            }
        }

        if (appts.isNotEmpty()) {
            sb.append("- Appointment history (latest first):\n")
            for (a in appts) {
                val date = a.startAt.atZone(ZoneId.systemDefault()).toLocalDate().format(dtFmt)
                sb.append("  • ").append(date)
                    .append(" | with Dr. ").append(a.doctor.firstName).append(' ').append(a.doctor.lastName)
                    .append(" | status: ").append(a.status.name)
                a.reason?.takeIf { it.isNotBlank() }?.let { sb.append(" | reason: ").append(it.trim()) }
                sb.append('\n')
            }
        }

        if (docs.isNotEmpty()) {
            sb.append("- Patient documents metadata:\n")
            for (d in docs) {
                sb.append("  • ").append(d.date.format(dtFmt)).append(" | ").append(d.title).append('\n')
            }
        }

        if (includeDocumentSnippets && docs.isNotEmpty()) {
            sb.append("- Extracted snippets from latest documents:\n")
            for (d in docs.take(3)) {
                val r = documentStorage.getFileResource(d.filePath) ?: continue
                val text = pdfTextExtraction.extractText(r).trim().take(1200)
                if (text.isBlank()) continue
                sb.append("  • ").append(d.title).append(": ").append(text.replace('\n', ' ')).append('\n')
            }
        }

        return sb.toString().trim()
    }

    fun shouldInjectDocumentSnippets(latestUserText: String): Boolean {
        val t = latestUserText.lowercase()
        return listOf(
            "document", "documents", "lab", "report", "analysis", "result",
            "pdf", "file", "scan", "xray", "mri", "ct", "ultrasound"
        ).any { t.contains(it) }
    }

    /** Selective retrieval for tool-based orchestration. */
    fun getPatientContextJson(patient: PatientProfile, intent: Intent): Map<String, Any?> {
        val patientId = patient.id ?: return emptyMap()
        val forms = patientForms.findByPatientIdOrderByDateDesc(patientId).take(if (intent == Intent.SYMPTOMS) 6 else 3)
        val appts = appointments.findByPatientId(patientId).take(if (intent == Intent.APPOINTMENTS) 12 else 5)
        val conditions = mutableListOf<String>()
        val meds = mutableListOf<String>()
        val complaints = mutableListOf<String>()
        for (f in forms) {
            f.diagnosisDisplay?.takeIf { it.isNotBlank() }?.let { conditions += it.trim() }
                ?: f.diagnosis?.takeIf { it.isNotBlank() }?.let { conditions += it.trim() }
            f.treatment?.takeIf { it.isNotBlank() }?.let { meds += it.trim() }
            f.complaints?.takeIf { it.isNotBlank() }?.let { complaints += it.trim() }
        }
        return mapOf(
            "patientId" to patientId,
            "language" to patient.language,
            "chronicDisease" to patient.chronicDisease,
            "recentComplaints" to complaints.distinct().take(8),
            "conditions" to conditions.distinct().take(8),
            "medications" to meds.distinct().take(8),
            "appointments" to appts.map {
                mapOf(
                    "date" to it.startAt.atZone(ZoneId.systemDefault()).toLocalDate().format(dtFmt),
                    "doctorName" to "${it.doctor.firstName} ${it.doctor.lastName}".trim(),
                    "status" to it.status.name,
                    "reason" to it.reason
                )
            }
        )
    }

    fun getPatientDocumentsJson(patient: PatientProfile, includeSnippets: Boolean, limit: Int = 5): Map<String, Any?> {
        val patientId = patient.id ?: return mapOf("documents" to emptyList<Map<String, Any?>>())
        val docs = patientDocuments.listForPatient(patientId)
            .filter { !it.isChatAttachment && it.filePath.isNotBlank() }
            .take(limit.coerceIn(1, 12))
        val payload = docs.map { d ->
            val snippet = if (includeSnippets) {
                val r = documentStorage.getFileResource(d.filePath)
                val txt = r?.let { pdfTextExtraction.extractText(it).trim().take(1000) }.orEmpty()
                txt.ifBlank { null }
            } else null
            mapOf(
                "id" to d.id,
                "title" to d.title,
                "date" to d.date.format(dtFmt),
                "snippet" to snippet
            )
        }
        return mapOf("documents" to payload)
    }
}

