package com.shifa.repo

import com.shifa.domain.ConsultationNote
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ConsultationNoteRepository : JpaRepository<ConsultationNote, Long> {
    fun findByDoctorIdAndPatientIdOrderByCreatedAtDesc(
        doctorId: Long,
        patientId: Long
    ): List<ConsultationNote>

    fun findByAppointmentIdOrderByCreatedAtAsc(appointmentId: Long): List<ConsultationNote>
    fun findFirstByAppointmentIdOrderByCreatedAtDesc(appointmentId: Long): ConsultationNote?

    @org.springframework.data.jpa.repository.Query("SELECT n FROM ConsultationNote n WHERE n.doctorId = :doctorId")
    fun findByDoctorId(@Param("doctorId") doctorId: Long): List<ConsultationNote>

    @org.springframework.data.jpa.repository.Query("SELECT n FROM ConsultationNote n WHERE n.patientId = :patientId")
    fun findByPatientId(@Param("patientId") patientId: Long): List<ConsultationNote>

    @Query(
        """SELECT COUNT(n) FROM ConsultationNote n WHERE n.doctorId = :doctorId
           AND n.createdAt >= :start AND n.createdAt < :endExclusive"""
    )
    fun countByDoctorInDateRange(
        @Param("doctorId") doctorId: Long,
        @Param("start") start: java.time.Instant,
        @Param("endExclusive") endExclusive: java.time.Instant,
    ): Long

    @Query(
        """SELECT COUNT(n) FROM ConsultationNote n WHERE n.doctorId = :doctorId"""
    )
    fun countByDoctorAllTime(@Param("doctorId") doctorId: Long): Long

    @Query("SELECT MAX(n.createdAt) FROM ConsultationNote n WHERE n.doctorId = :doctorId")
    fun findMaxCreatedAtByDoctorId(@Param("doctorId") doctorId: Long): java.time.Instant?

    @Query("""
        SELECT n FROM ConsultationNote n WHERE n.doctorId = :doctorId
        AND n.createdAt >= :start AND n.createdAt < :endExclusive""")
    fun listByDoctorCreatedBetween(
        @Param("doctorId") doctorId: Long,
        @Param("start") start: java.time.Instant,
        @Param("endExclusive") endExclusive: java.time.Instant,
    ): List<ConsultationNote>
}
