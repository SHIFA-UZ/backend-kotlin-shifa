package com.shifa.ai.application

import com.shifa.ai.domain.AiDraftNote
import com.shifa.ai.infrastructure.AiDraftNoteRepository
import java.time.Instant
import org.springframework.stereotype.Service

/**
 * The feature's door to AiDraftNote records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [AiDraftNoteRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class AiDraftNotes(private val repo: AiDraftNoteRepository) {
    fun findByDoctorId(doctorId: Long): List<AiDraftNote> = repo.findByDoctorId(doctorId)
    fun findByPatientId(patientId: Long): List<AiDraftNote> = repo.findByPatientId(patientId)
    fun findByConsultationIdAndStatusOrderByCreatedAtDesc(consultationId: Long, status: AiDraftNote.Status): List<AiDraftNote> = repo.findByConsultationIdAndStatusOrderByCreatedAtDesc(consultationId, status)
    fun countByDoctorInDateRange(doctorId: Long, start: Instant, endExclusive: Instant): Long = repo.countByDoctorInDateRange(doctorId, start, endExclusive)
    fun countByDoctorAllTime(doctorId: Long): Long = repo.countByDoctorAllTime(doctorId)
    fun findMaxCreatedAtByDoctorId(doctorId: Long): Instant? = repo.findMaxCreatedAtByDoctorId(doctorId)
    fun deleteAll(entities: Iterable<AiDraftNote>) = repo.deleteAll(entities)
    fun deleteAll() = repo.deleteAll()
}
