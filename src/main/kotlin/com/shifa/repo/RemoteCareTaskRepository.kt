package com.shifa.repo

import com.shifa.domain.RemoteCareTask
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
}
