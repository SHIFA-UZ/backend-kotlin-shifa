package com.shifa.repo

import com.shifa.domain.RemoteCareTask
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface RemoteCareTaskRepository : JpaRepository<RemoteCareTask, Long> {
    fun findByDoctorIdOrderByCreatedAtDesc(doctorId: Long): List<RemoteCareTask>
    fun findByPatientIdOrderByCreatedAtDesc(patientId: Long): List<RemoteCareTask>
    fun findByPatientIdAndStatus(patientId: Long, status: RemoteCareTask.Status): List<RemoteCareTask>
    fun findByDoctorIdAndStatus(doctorId: Long, status: RemoteCareTask.Status): List<RemoteCareTask>
    
    // Include tasks that are active and either:
    // 1. Have already started (startDate <= today) and haven't ended (endDate is null or >= today), OR
    // 2. Will start in the future (startDate > today) - patients should see upcoming tasks
    @Query("SELECT t FROM RemoteCareTask t WHERE t.patient.id = :patientId AND t.status = :status AND ((t.startDate <= :date AND (t.endDate IS NULL OR t.endDate >= :date)) OR t.startDate > :date)")
    fun findActiveTasksForPatient(
        @Param("patientId") patientId: Long,
        @Param("status") status: RemoteCareTask.Status,
        @Param("date") date: LocalDate
    ): List<RemoteCareTask>

    @Query(
        """SELECT COUNT(t) FROM RemoteCareTask t WHERE t.doctor.id = :doctorId
           AND t.createdAt >= :start AND t.createdAt < :endExclusive"""
    )
    fun countByDoctorInDateRange(
        @Param("doctorId") doctorId: Long,
        @Param("start") start: Instant,
        @Param("endExclusive") endExclusive: Instant,
    ): Long

    @Query(
        """SELECT COUNT(t) FROM RemoteCareTask t WHERE t.doctor.id = :doctorId"""
    )
    fun countByDoctorAllTime(@Param("doctorId") doctorId: Long): Long

    @Query("SELECT MAX(t.updatedAt) FROM RemoteCareTask t WHERE t.doctor.id = :doctorId")
    fun findMaxUpdatedAtByDoctorId(@Param("doctorId") doctorId: Long): Instant?

    @Query("""
        SELECT t FROM RemoteCareTask t WHERE t.doctor.id = :doctorId
        AND t.createdAt >= :start AND t.createdAt < :endExclusive""")
    fun listByDoctorCreatedBetween(
        @Param("doctorId") doctorId: Long,
        @Param("start") start: Instant,
        @Param("endExclusive") endExclusive: Instant,
    ): List<RemoteCareTask>

    @Query(
        """
        SELECT DISTINCT t.patient.id FROM RemoteCareTask t
        WHERE t.status = :status AND t.patient.id IN :patientIds
        """
    )
    fun findActiveTaskPatientIdsIn(
        @Param("patientIds") patientIds: Collection<Long>,
        @Param("status") status: RemoteCareTask.Status = RemoteCareTask.Status.ACTIVE,
    ): List<Long>
}
