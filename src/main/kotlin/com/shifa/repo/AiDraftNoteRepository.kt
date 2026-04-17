package com.shifa.repo

import com.shifa.domain.AiDraftNote
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface AiDraftNoteRepository : JpaRepository<AiDraftNote, UUID> {
    fun findByDoctorIdAndStatusOrderByCreatedAtDesc(
        doctorId: Long,
        status: AiDraftNote.Status
    ): List<AiDraftNote>

    fun findByStatusAndCreatedAtBefore(status: AiDraftNote.Status, before: Instant): List<AiDraftNote>

    fun findByDoctorId(doctorId: Long): List<AiDraftNote>
    @org.springframework.data.jpa.repository.Query("SELECT a FROM AiDraftNote a WHERE a.patientId = :patientId")
    fun findByPatientId(@org.springframework.data.repository.query.Param("patientId") patientId: Long): List<AiDraftNote>

    fun findByConsultationIdAndStatusOrderByCreatedAtDesc(
        consultationId: Long,
        status: AiDraftNote.Status
    ): List<AiDraftNote>
}
