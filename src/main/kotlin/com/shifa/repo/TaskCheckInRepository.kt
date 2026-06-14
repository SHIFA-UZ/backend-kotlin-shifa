package com.shifa.repo

import com.shifa.domain.TaskCheckIn
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.time.LocalTime

interface TaskCheckInRepository : JpaRepository<TaskCheckIn, Long> {
    fun findByTaskIdOrderByScheduledDateAscScheduledTimeAsc(taskId: Long): List<TaskCheckIn>
    fun findByTaskIdAndScheduledDate(taskId: Long, scheduledDate: LocalDate): List<TaskCheckIn>
    fun findByTaskIdAndStatus(taskId: Long, status: TaskCheckIn.Status): List<TaskCheckIn>
    
    @Query("SELECT c FROM TaskCheckIn c WHERE c.task.id = :taskId AND c.scheduledDate = :date AND c.status = 'PENDING'")
    fun findPendingCheckInsForDate(
        @Param("taskId") taskId: Long,
        @Param("date") date: LocalDate
    ): List<TaskCheckIn>

    /** PENDING check-ins due for reminder (scheduled at given date, time in range, reminder not yet sent). */
    @Query("""
        SELECT c FROM TaskCheckIn c
        WHERE c.status = 'PENDING' AND c.reminderSentAt IS NULL
          AND c.scheduledDate = :date
          AND c.scheduledTime IS NOT NULL
          AND c.scheduledTime >= :timeFrom AND c.scheduledTime <= :timeTo
    """)
    fun findPendingForReminder(
        @Param("date") date: LocalDate,
        @Param("timeFrom") timeFrom: LocalTime,
        @Param("timeTo") timeTo: LocalTime
    ): List<TaskCheckIn>

    /** Same as findPendingForReminder but with task and patient eagerly loaded for FCM. */
    @Query("""
        SELECT c FROM TaskCheckIn c
        JOIN FETCH c.task t
        JOIN FETCH t.patient
        WHERE c.status = 'PENDING' AND c.reminderSentAt IS NULL
          AND c.scheduledDate = :date
          AND c.scheduledTime IS NOT NULL
          AND c.scheduledTime >= :timeFrom AND c.scheduledTime <= :timeTo
    """)
    fun findPendingForReminderWithTaskAndPatient(
        @Param("date") date: LocalDate,
        @Param("timeFrom") timeFrom: LocalTime,
        @Param("timeTo") timeTo: LocalTime
    ): List<TaskCheckIn>

    /** Same but scoped to one doctor's tasks (legacy; prefer findPendingForReminderWithTaskAndPatient). */
    @Query("""
        SELECT c FROM TaskCheckIn c
        JOIN FETCH c.task t
        JOIN FETCH t.patient
        WHERE t.doctor.id = :doctorId
          AND c.status = 'PENDING' AND c.reminderSentAt IS NULL
          AND c.scheduledDate = :date
          AND c.scheduledTime IS NOT NULL
          AND c.scheduledTime >= :timeFrom AND c.scheduledTime <= :timeTo
    """)
    fun findPendingForReminderWithTaskAndPatientByDoctor(
        @Param("doctorId") doctorId: Long,
        @Param("date") date: LocalDate,
        @Param("timeFrom") timeFrom: LocalTime,
        @Param("timeTo") timeTo: LocalTime
    ): List<TaskCheckIn>

    /**
     * PENDING check-ins in a date window (patient TZ applied in service).
     * ±1 day from UTC covers all IANA offsets for "today" in the patient's timezone.
     */
    @Query("""
        SELECT c FROM TaskCheckIn c
        JOIN FETCH c.task t
        JOIN FETCH t.patient p
        WHERE c.status = 'PENDING' AND c.reminderSentAt IS NULL
          AND c.scheduledTime IS NOT NULL
          AND c.scheduledDate >= :fromDate
          AND c.scheduledDate <= :toDate
    """)
    fun findPendingForReminderWithTaskAndPatientInDateRange(
        @Param("fromDate") fromDate: LocalDate,
        @Param("toDate") toDate: LocalDate,
    ): List<TaskCheckIn>
}
