package com.shifa.tasks.application

import com.shifa.tasks.domain.TaskCheckIn
import com.shifa.tasks.infrastructure.TaskCheckInRepository
import java.time.LocalDate
import java.util.Optional
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The feature's door to TaskCheckIn records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [TaskCheckInRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class TaskCheckIns(private val repo: TaskCheckInRepository) {
    fun findByTaskIdOrderByScheduledDateAscScheduledTimeAsc(taskId: Long): List<TaskCheckIn> = repo.findByTaskIdOrderByScheduledDateAscScheduledTimeAsc(taskId)
    @Transactional(readOnly = true)
    fun findByTaskIdIn(taskIds: Collection<Long>): List<TaskCheckIn> = if (taskIds.isEmpty()) emptyList() else repo.findByTaskIdIn(taskIds)
    fun findPendingForReminderWithTaskAndPatientInDateRange(fromDate: LocalDate, toDate: LocalDate): List<TaskCheckIn> = repo.findPendingForReminderWithTaskAndPatientInDateRange(fromDate, toDate)
    fun findById(id: Long): Optional<TaskCheckIn> = repo.findById(id)
    fun save(entity: TaskCheckIn): TaskCheckIn = repo.save(entity)
}
