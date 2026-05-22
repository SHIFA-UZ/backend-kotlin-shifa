package com.shifa.repo

import com.shifa.domain.AiDraftNote
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
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

    @Query(
        """SELECT COUNT(a) FROM AiDraftNote a WHERE a.doctorId = :doctorId
           AND a.createdAt >= :start AND a.createdAt < :endExclusive"""
    )
    fun countByDoctorInDateRange(
        @Param("doctorId") doctorId: Long,
        @Param("start") start: Instant,
        @Param("endExclusive") endExclusive: Instant,
    ): Long

    @Query("SELECT COUNT(a) FROM AiDraftNote a WHERE a.doctorId = :doctorId")
    fun countByDoctorAllTime(@Param("doctorId") doctorId: Long): Long

    @Query("SELECT MAX(a.createdAt) FROM AiDraftNote a WHERE a.doctorId = :doctorId")
    fun findMaxCreatedAtByDoctorId(@Param("doctorId") doctorId: Long): Instant?
}
