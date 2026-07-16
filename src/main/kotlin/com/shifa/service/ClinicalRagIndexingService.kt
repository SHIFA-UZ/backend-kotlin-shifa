package com.shifa.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.shifa.clinical.ClinicalRagSources
import com.shifa.clinical.DentalChartExpansion
import com.shifa.domain.Appointment
import com.shifa.domain.ConsultationNote
import com.shifa.domain.PatientForm
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.ConsultationNoteRepository
import com.shifa.repo.PatientFormRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Indexes structured clinical text into [clinical_rag_chunks] for semantic retrieval.
 */
@Service
class ClinicalRagIndexingService(
    private val patientForms: PatientFormRepository,
    private val consultationNotes: ConsultationNoteRepository,
    private val appointments: AppointmentRepository,
    private val jdbcRepository: ClinicalRagJdbcRepository,
    private val embeddings: OpenAiEmbeddingService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()
    private val indexLocks = ConcurrentHashMap<String, Any>()

    private fun indexLockKey(patientId: Long, sourceType: String, sourceRecordId: Long): String =
        "$patientId:$sourceType:$sourceRecordId"

    private inline fun <T> withIndexLock(
        patientId: Long,
        sourceType: String,
        sourceRecordId: Long,
        block: () -> T,
    ): T {
        val lock = indexLocks.computeIfAbsent(indexLockKey(patientId, sourceType, sourceRecordId)) { Any() }
        synchronized(lock) {
            return block()
        }
    }

    data class ReindexSummary(
        val patientForms: Int = 0,
        val consultationNotes: Int = 0,
        val appointmentDental: Int = 0,
    )

    fun deleteChunksForPatientForm(formId: Long) {
        try {
            val form = patientForms.findById(formId).orElse(null) ?: return
            val patientId = form.patient?.id ?: return
            jdbcRepository.deleteBySource(patientId, ClinicalRagSources.FORM_0252, formId)
        } catch (ex: Exception) {
            log.warn("clinical RAG delete form chunks {} failed: {}", formId, ex.message)
        }
    }

    fun reindexAllForPatient(patientId: Long): ReindexSummary {
        var fc = 0
        var nc = 0
        var ac = 0
        for (f in patientForms.findByPatientIdOrderByDateDesc(patientId)) {
            if (f.templateId == "025-2" && f.id != null) {
                reindexPatientForm(f.id!!)
                fc++
            }
        }
        for (n in consultationNotes.findByPatientId(patientId)) {
            if (n.id != null) {
                reindexConsultationNote(n.id!!)
                nc++
            }
        }
        for (a in appointments.findByPatientId(patientId)) {
            if (!a.dentalDocumentation.isNullOrBlank()) {
                reindexAppointmentDentalDocumentation(a.id)
                ac++
            }
        }
        return ReindexSummary(patientForms = fc, consultationNotes = nc, appointmentDental = ac)
    }

    fun reindexPatientForm(formId: Long) {
        try {
            val form = patientForms.findById(formId).orElse(null) ?: return
            val patientId = form.patient?.id ?: return
            withIndexLock(patientId, ClinicalRagSources.FORM_0252, formId) {
                if (form.templateId != "025-2") {
                    jdbcRepository.replaceChunks(patientId, ClinicalRagSources.FORM_0252, formId, emptyList())
                    return
                }

                val chunks = build0252Chunks(form).map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_CHUNKS_PER_SOURCE)
                if (chunks.isEmpty()) {
                    jdbcRepository.replaceChunks(patientId, ClinicalRagSources.FORM_0252, formId, emptyList())
                    return
                }
                val vectors = embeddings.embedTexts(chunks)
                if (vectors.size != chunks.size) {
                    log.warn("Embedding count mismatch for form {}; skipping index", formId)
                    return
                }
                jdbcRepository.replaceChunks(patientId, ClinicalRagSources.FORM_0252, formId, chunks.zip(vectors))
            }
        } catch (ex: Exception) {
            log.warn("clinical RAG index form {} failed: {}", formId, ex.message)
        }
    }
    fun reindexConsultationNote(noteId: Long) {
        try {
            val note = consultationNotes.findById(noteId).orElse(null) ?: return
            val patientId = note.patientId
            withIndexLock(patientId, ClinicalRagSources.CONSULTATION_NOTE, noteId) {
                val body = combineConsultationNote(note).trim()
                if (body.isEmpty()) {
                    jdbcRepository.replaceChunks(patientId, ClinicalRagSources.CONSULTATION_NOTE, noteId, emptyList())
                    return
                }
                val chunks = splitTextChunks(body, MAX_CHUNK_CHARS).take(MAX_CHUNKS_PER_SOURCE)
                if (chunks.isEmpty()) {
                    jdbcRepository.replaceChunks(patientId, ClinicalRagSources.CONSULTATION_NOTE, noteId, emptyList())
                    return
                }
                val vectors = embeddings.embedTexts(chunks)
                if (vectors.size != chunks.size) return
                jdbcRepository.replaceChunks(patientId, ClinicalRagSources.CONSULTATION_NOTE, noteId, chunks.zip(vectors))
            }
        } catch (ex: Exception) {
            log.warn("clinical RAG index note {} failed: {}", noteId, ex.message)
        }
    }

    fun reindexAppointmentDentalDocumentation(appointmentId: Long) {
        try {
            val appt = appointments.findById(appointmentId).orElse(null) ?: return
            val patientKey = requireNotNull(appt.patient.id) { "appointment patient id required" }
            withIndexLock(patientKey, ClinicalRagSources.APPOINTMENT_DENTAL, appointmentId) {
                val raw = appt.dentalDocumentation?.trim().orEmpty()
                if (raw.isEmpty()) {
                    jdbcRepository.replaceChunks(patientKey, ClinicalRagSources.APPOINTMENT_DENTAL, appointmentId, emptyList())
                    return
                }

                val chunks = buildAppointmentDentalChunks(appt, raw).take(MAX_CHUNKS_PER_SOURCE)
                if (chunks.isEmpty()) {
                    jdbcRepository.replaceChunks(patientKey, ClinicalRagSources.APPOINTMENT_DENTAL, appointmentId, emptyList())
                    return
                }
                val vectors = embeddings.embedTexts(chunks)
                if (vectors.size != chunks.size) return
                jdbcRepository.replaceChunks(patientKey, ClinicalRagSources.APPOINTMENT_DENTAL, appointmentId, chunks.zip(vectors))
            }
        } catch (ex: Exception) {
            log.warn("clinical RAG index appointment dental {} failed: {}", appointmentId, ex.message)
        }
    }

    private fun build0252Chunks(form: PatientForm): List<String> {
        val id = form.id ?: 0L
        val header = buildString {
            append("Form 025-2 medical record (internal form id ").append(id).append("). ")
            append("Form date: ${form.date}. ")
            form.formNumber?.let { append("Form number: $it. ") }
            form.diagnosisDisplay?.takeIf { it.isNotBlank() }?.let { append("Diagnosis (display): $it. ") }
            form.diagnosis?.takeIf { it.isNotBlank() && form.diagnosisDisplay.isNullOrBlank() }
                ?.let { append("Diagnosis: $it. ") }
        }.trim()

        val chunks = ArrayList<String>()
        chunks += header

        for (line in DentalChartExpansion.expandDentalChartToLines(form.dentalChart)) {
            chunks += "Form 025-2 dental chart (patient tooth record). $line"
        }

        for (fu in form.followups ?: emptyList()) {
            val d = fu["date"]?.trim().orEmpty()
            val findings = fu["clinicalFindings"]?.trim().orEmpty()
            val who = fu["doctorName"]?.trim().orEmpty()
            if (findings.isEmpty()) continue
            val line = buildString {
                append("Form 025-2 follow-up visit")
                if (d.isNotEmpty()) append(" dated $d")
                if (who.isNotEmpty()) append(" ($who)")
                append(": ")
                append(findings)
            }
            chunks += line
        }

        fun addNarrative(label: String, text: String?) {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return
            for (p in splitTextChunks("$label: $t", MAX_CHUNK_CHARS)) {
                chunks += "Form 025-2 narrative. $p"
            }
        }

        addNarrative("Complaints", form.complaints)
        addNarrative("Visual checkup", form.visualCheckup)
        addNarrative("Oral cavity condition", form.oralCavityCondition)
        addNarrative("Occlusion", form.occlusion)
        addNarrative("X-ray or laboratory summary", form.xrayLabData)
        addNarrative("Treatment performed or planned", form.treatment)
        addNarrative("Treatment result", form.treatmentResult)
        addNarrative("Recommendations", form.recommendations)
        addNarrative("Other illnesses", form.otherIllnesses)
        addNarrative("More clinical details", form.moreDetails)

        return chunks
    }

    private fun buildAppointmentDentalChunks(appt: Appointment, rawJson: String): List<String> {
        val chunks = ArrayList<String>()
        val root: JsonNode = try {
            mapper.readTree(rawJson)
        } catch (_: Exception) {
            chunks += "Dental visit documentation (appointment ${appt.id}) unstructured text: ${rawJson.take(1800)}"
            return chunks
        }

        val header =
            "Dental visit documentation JSON (appointment ${appt.id}, patient ${requireNotNull(appt.patient.id)}, doctor ${appt.doctor.id})."
        chunks += header

        val teethNode = root.get("teeth")
        if (teethNode != null && teethNode.isObject) {
            val teethMap: Map<String, Any?> = mapper.convertValue(
                teethNode,
                object : TypeReference<Map<String, Any?>>() {}
            )
            for (line in DentalChartExpansion.expandAppointmentTeethMap(teethMap)) {
                chunks += line
            }
        }

        val notes = root.path("notes").asText("").trim()
        if (notes.isNotEmpty()) {
            for (p in splitTextChunks("Dental visit free-text notes: $notes", MAX_CHUNK_CHARS)) {
                chunks += p
            }
        }
        return chunks
    }

    private fun combineConsultationNote(n: ConsultationNote): String {
        val body = n.body?.trim().orEmpty()
        if (body.isNotEmpty()) return body
        return listOfNotNull(
            n.subjective?.takeIf { it.isNotBlank() }?.let { "Subjective: ${it.trim()}" },
            n.assessment?.takeIf { it.isNotBlank() }?.let { "Assessment: ${it.trim()}" },
            n.plan?.takeIf { it.isNotBlank() }?.let { "Plan: ${it.trim()}" },
        ).joinToString("\n\n")
    }

    companion object {
        private const val MAX_CHUNK_CHARS = 1100
        private const val MAX_CHUNKS_PER_SOURCE = 72

        private fun splitTextChunks(text: String, maxLen: Int): List<String> {
            val t = text.trim()
            if (t.isEmpty()) return emptyList()
            if (t.length <= maxLen) return listOf(t)
            val parts = ArrayList<String>()
            var i = 0
            while (i < t.length) {
                val end = minOf(i + maxLen, t.length)
                parts += t.substring(i, end)
                i = end
            }
            return parts
        }
    }
}
