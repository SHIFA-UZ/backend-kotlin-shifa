package com.shifa.service

import com.shifa.ai.AiResponseLabelGenerator
import com.shifa.ai.StructuredNoteParser
import com.shifa.config.OpenAiProperties
import com.shifa.domain.AiDraftNote
import com.shifa.domain.ConsultationNote
import com.shifa.repo.AiDraftNoteRepository
import com.shifa.repo.ConsultationNoteRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class AiDraftNoteService(
    private val draftRepo: AiDraftNoteRepository,
    private val consultationNoteRepo: ConsultationNoteRepository,
    private val openAiProps: OpenAiProperties,
    private val clinicalRagIndexingService: ClinicalRagIndexingService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
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
        appointmentIdOverride: Long? = null
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
            body = parsed.body,
            source = "AI_DRAFT"
        )
        val savedNote = consultationNoteRepo.save(note)
        draft.status = AiDraftNote.Status.CONFIRMED
        draftRepo.save(draft)
        savedNote.id?.let { clinicalRagIndexingService.reindexConsultationNote(it) }
        log.info("AiDraftNote confirmed: draftId={}, doctorId={}, consultationNoteId={}", draftId, doctorId, savedNote.id)
        return savedNote
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

    fun getDraftById(draftId: UUID): AiDraftNote? = draftRepo.findById(draftId).orElse(null)

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
