package com.shifa.ai.application


import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.shifa.ai.domain.AiDraftNote
import com.shifa.ai.domain.AiResponseLabelGenerator
import com.shifa.ai.domain.StructuredNoteParser
import com.shifa.ai.infrastructure.AiDraftNoteRepository
import com.shifa.appointments.domain.ConsultationNote
import com.shifa.shared.config.OpenAiProperties
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AiDraftNoteService(
    private val draftRepo: AiDraftNoteRepository,
    private val consultationNoteRepo: com.shifa.appointments.application.ConsultationNotes,
    private val openAiProps: OpenAiProperties,
    private val clinicalRagIndexingService: ClinicalRagIndexingService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val INTELLIGENCE_DICTATION_LABEL = "Intelligence dictation"
        const val MAX_DICTATION_CHARS = 20000
    }
    private val mapper = jacksonObjectMapper()

    @Transactional
    fun createDraft(
        doctorId: Long,
        patientId: Long?,
        consultationId: Long?,
        aiResponseText: String,
        aiLabel: String? = null
    ): AiDraftNote {
        val label = aiLabel ?: AiResponseLabelGenerator.generateLabel(aiResponseText)
        val draft = AiDraftNote(
            doctorId = doctorId,
            patientId = patientId,
            consultationId = consultationId,
            aiResponseText = aiResponseText,
            aiLabel = label,
            status = AiDraftNote.Status.GENERATED,
            modelVersion = openAiProps.model,
            promptVersion = openAiProps.promptVersion
        )
        val saved = draftRepo.save(draft)
        log.info("AiDraftNote created: id={}, doctorId={}, label={}", saved.id, doctorId, label)
        return saved
    }

    @Transactional
    fun confirmDraft(
        draftId: UUID,
        doctorId: Long,
        patientIdOverride: Long? = null,
        appointmentIdOverride: Long? = null,
        fullBodyOverride: String? = null,
    ): ConsultationNote {
        val draft = draftRepo.findById(draftId).orElseThrow { NoSuchElementException("Draft not found: $draftId") }
        if (draft.doctorId != doctorId) throw SecurityException("Draft does not belong to this doctor")
        if (draft.status != AiDraftNote.Status.GENERATED) throw IllegalStateException("Draft already confirmed or discarded")
        val patientId = patientIdOverride ?: draft.patientId ?: throw IllegalStateException("Draft has no patient; provide patientId when confirming")
        val appointmentId = appointmentIdOverride ?: draft.consultationId
        val parsed = StructuredNoteParser.parse(draft.aiResponseText)
        val note = ConsultationNote(
            doctorId = draft.doctorId,
            patientId = patientId,
            appointmentId = appointmentId,
            aiDraftNoteId = draft.id,
            subjective = parsed.subjective,
            assessment = parsed.assessment,
            plan = parsed.plan,
            body = fullBodyOverride?.takeIf { it.isNotBlank() } ?: parsed.body,
            source = "AI_DRAFT"
        )
        val savedNote = consultationNoteRepo.save(note)
        draft.status = AiDraftNote.Status.CONFIRMED
        draftRepo.save(draft)
        savedNote.id?.let { clinicalRagIndexingService.reindexConsultationNote(it) }
        log.info("AiDraftNote confirmed: draftId={}, doctorId={}, consultationNoteId={}", draftId, doctorId, savedNote.id)
        return savedNote
    }

    /**
     * Auto-confirm generated scribe drafts for an appointment so they land in documentation
     * without a separate doctor Confirm tap.
     */
    @Transactional
    fun confirmGeneratedScribeDrafts(
        appointmentId: Long,
        doctorId: Long,
        label: String,
    ): List<ConsultationNote> {
        val drafts = draftRepo.findByConsultationIdAndStatusOrderByCreatedAtDesc(
            appointmentId,
            AiDraftNote.Status.GENERATED
        ).filter { it.aiLabel == label && it.doctorId == doctorId }
        return drafts.map { confirmDraft(it.id!!, doctorId, fullBodyOverride = it.aiResponseText) }
    }

    @Transactional
    fun discardDraft(draftId: UUID, doctorId: Long) {
        val draft = draftRepo.findById(draftId).orElseThrow { NoSuchElementException("Draft not found: $draftId") }
        if (draft.doctorId != doctorId) throw SecurityException("Draft does not belong to this doctor")
        if (draft.status != AiDraftNote.Status.GENERATED) throw IllegalStateException("Draft already confirmed or discarded")
        draft.status = AiDraftNote.Status.DISCARDED
        draftRepo.save(draft)
        log.info("AiDraftNote discarded: draftId={}, doctorId={}", draftId, doctorId)
    }

    @Transactional(readOnly = true)
    fun getDraftById(draftId: UUID): AiDraftNote? = draftRepo.findById(draftId).orElse(null)

    @Transactional(readOnly = true)
    fun hasActiveScribeDraft(appointmentId: Long, label: String): Boolean {
        return draftRepo.findByConsultationId(appointmentId).any { draft ->
            draft.aiLabel == label && draft.status != AiDraftNote.Status.DISCARDED
        }
    }

    @Transactional(readOnly = true)
    fun hasGeneratedScribeDraft(appointmentId: Long, label: String): Boolean {
        return draftRepo.findByConsultationIdAndStatusOrderByCreatedAtDesc(
            appointmentId,
            AiDraftNote.Status.GENERATED,
        ).any { it.aiLabel == label }
    }

    /**
     * Attach ICD suggestions JSON to an existing draft. This is optional metadata and should not
     * affect draft confirmation or existing workflows.
     */
    @Transactional
    fun attachIcdSuggestions(draft: AiDraftNote, suggestions: List<IcdSuggestion>) {
        try {
            val payload = mapper.writeValueAsString(
                suggestions.map { s ->
                    mapOf(
                        "code" to s.code,
                        "title" to s.title,
                        "confidence" to s.confidence
                    )
                }
            )
            draft.icdSuggestionsJson = payload
            draftRepo.save(draft)
        } catch (e: Exception) {
            log.warn("Failed to attach ICD suggestions to draft {}: {}", draft.id, e.message)
        }
    }

    /**
     * Adds the doctor's own words to one generated draft for this visit.
     * Does not confirm the draft into a consultation note.
     */
    @Transactional
    fun appendIntelligenceDictation(
        doctorId: Long,
        patientId: Long,
        appointmentId: Long,
        addition: String,
    ): AiDraftNote {
        val text = addition.trim()
        require(text.isNotEmpty()) { "Dictation is empty" }
        val existing = draftRepo.findByConsultationIdAndStatusOrderByCreatedAtDesc(
            appointmentId,
            AiDraftNote.Status.GENERATED,
        ).firstOrNull { it.doctorId == doctorId && it.aiLabel == INTELLIGENCE_DICTATION_LABEL }
        if (existing != null) {
            val combined = existing.aiResponseText.trimEnd() + "\n\n" + text
            require(combined.length <= MAX_DICTATION_CHARS) { "Draft is too long" }
            existing.aiResponseText = combined
            return draftRepo.save(existing)
        }
        require(text.length <= MAX_DICTATION_CHARS) { "Draft is too long" }
        return createDraft(
            doctorId = doctorId,
            patientId = patientId,
            consultationId = appointmentId,
            aiResponseText = text,
            aiLabel = INTELLIGENCE_DICTATION_LABEL,
        )
    }

    @Transactional(readOnly = true)
    fun intelligenceDictation(doctorId: Long, appointmentId: Long): AiDraftNote? =
        draftRepo.findByConsultationIdAndStatusOrderByCreatedAtDesc(
            appointmentId,
            AiDraftNote.Status.GENERATED,
        ).firstOrNull { it.doctorId == doctorId && it.aiLabel == INTELLIGENCE_DICTATION_LABEL }

    /** Marks GENERATED drafts older than 30 days as DISCARDED (for background cleanup). */
    @Transactional
    fun cleanupOldDrafts() {
        val cutoff = Instant.now().minus(30, ChronoUnit.DAYS)
        val old = draftRepo.findByStatusAndCreatedAtBefore(AiDraftNote.Status.GENERATED, cutoff)
        old.forEach { it.status = AiDraftNote.Status.DISCARDED }
        if (old.isNotEmpty()) {
            draftRepo.saveAll(old)
            log.info("AiDraftNote cleanup: marked {} old drafts as DISCARDED", old.size)
        }
    }
}
