package com.shifa.web

import com.shifa.domain.Notification
import com.shifa.domain.PatientProfile
import com.shifa.domain.RemoteCareTask
import com.shifa.domain.SubscriptionFeature
import com.shifa.domain.TaskCheckIn
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.NotificationRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.repo.RemoteCareTaskRepository
import com.shifa.repo.TaskCheckInRepository
import com.shifa.security.DoctorPrincipal
import com.shifa.security.PatientPrincipal
import com.shifa.service.FcmService
import com.shifa.service.SubscriptionTierService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

@RestController
@RequestMapping("/api/tasks")
class RemoteCareTaskController(
    private val taskRepo: RemoteCareTaskRepository,
    private val checkInRepo: TaskCheckInRepository,
    private val patientRepo: PatientProfileRepository,
    private val doctorRepo: DoctorProfileRepository,
    private val notificationRepo: NotificationRepository,
    private val fcmService: FcmService,
    private val subscriptionTierService: SubscriptionTierService
) {

    /** Doctor-side remote-care tasks require PREMIUM. */
    private fun requireDoctorTaskAccess(principal: DoctorPrincipal) {
        subscriptionTierService.requireFeature(
            principal.profile.user,
            SubscriptionFeature.REMOTE_CARE_TASKS
        )
    }

    // ==================== DOCTOR ENDPOINTS ====================

    data class CreateTaskRequest(
        val patientId: Long,
        val taskName: String,
        val description: String?,
        val category: String,
        val timesPerDay: Int,
        val startTime: String?,
        val intervalHours: Int?,
        /** Optional explicit list of HH:mm slot times. When non-null/non-empty
         * this overrides startTime/intervalHours/timesPerDay-based scheduling. */
        val customTimes: List<String>? = null,
        val morningTime: String?,
        val afternoonTime: String?,
        val eveningTime: String?,
        val startDate: String,
        val endDate: String?,
        val durationDays: Int?,
        val inputType: String,
        val inputLabel: String?,
        val notesRequired: Boolean,
        val notesLabel: String?
    )

    data class TaskDto(
        val id: Long,
        val patientId: Long,
        val patientName: String,
        val patientTimeZone: String?,
        val taskName: String,
        val description: String?,
        val category: String,
        val status: String,
        val timesPerDay: Int,
        val startTime: String?,
        val intervalHours: Int?,
        val customTimes: List<String>? = null,
        val morningTime: String?,
        val afternoonTime: String?,
        val eveningTime: String?,
        val startDate: String,
        val endDate: String?,
        val durationDays: Int?,
        val inputType: String,
        val inputLabel: String?,
        val notesRequired: Boolean,
        val notesLabel: String?,
        val createdAt: String,
        val progress: TaskProgressDto?
    )

    data class TaskProgressDto(
        val totalCheckIns: Int,
        val completedCheckIns: Int,
        val pendingCheckIns: Int,
        val missedCheckIns: Int
    )

    /** Reusable task config (no patient, no dates) for "use as template". */
    data class TemplateDto(
        val taskName: String,
        val description: String?,
        val category: String,
        val timesPerDay: Int,
        val startTime: String?,
        val intervalHours: Int?,
        val customTimes: List<String>? = null,
        val morningTime: String?,
        val afternoonTime: String?,
        val eveningTime: String?,
        val inputType: String,
        val inputLabel: String?,
        val notesRequired: Boolean,
        val notesLabel: String?
    )

    data class CheckInDto(
        val id: Long,
        val scheduledDate: String,
        val scheduledTime: String?,
        val status: String,
        val numericValue: Double?,
        val textValue: String?,
        val booleanValue: Boolean?,
        val notes: String?,
        val completedAt: String?
    )

    @PostMapping
    fun createTask(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody req: CreateTaskRequest
    ): TaskDto {
        requireDoctorTaskAccess(principal)
        val doctor = principal.profile
        val patient = patientRepo.findById(req.patientId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found") }

        // Custom-times mode: doctor supplied explicit slot times. Validate
        // and normalize to a sorted, de-duplicated, comma-separated string.
        // When this is non-null, it drives the daily schedule and timesPerDay
        // is forced to match the entry count so the rest of the system stays
        // consistent.
        val parsedCustomTimes: List<LocalTime>? = parseCustomTimes(req.customTimes)
        val customTimesStored: String? = parsedCustomTimes?.joinToString(",") { fmtTime(it) }

        val timesPerDay = parsedCustomTimes?.size?.coerceAtLeast(1)
            ?: req.timesPerDay.coerceIn(1, 15)
        val startTimeParsed = parsedCustomTimes?.firstOrNull()
            ?: req.startTime?.let { LocalTime.parse(it) }
        val intervalHours = if (parsedCustomTimes != null) null else req.intervalHours?.coerceIn(1, 24)

        val task = RemoteCareTask(
            doctor = doctor,
            patient = patient,
            taskName = req.taskName,
            description = req.description,
            category = RemoteCareTask.Category.valueOf(req.category.uppercase()),
            status = RemoteCareTask.Status.ACTIVE,
            timesPerDay = timesPerDay,
            startTime = startTimeParsed,
            intervalHours = if (parsedCustomTimes == null && timesPerDay >= 2) {
                intervalHours ?: 1
            } else {
                null
            },
            customTimes = customTimesStored,
            morningTime = req.morningTime?.let { LocalTime.parse(it) },
            afternoonTime = req.afternoonTime?.let { LocalTime.parse(it) },
            eveningTime = req.eveningTime?.let { LocalTime.parse(it) },
            startDate = LocalDate.parse(req.startDate),
            endDate = req.endDate?.let { LocalDate.parse(it) },
            durationDays = req.durationDays,
            inputType = RemoteCareTask.InputType.valueOf(req.inputType.uppercase()),
            inputLabel = req.inputLabel,
            notesRequired = req.notesRequired,
            notesLabel = req.notesLabel
        )

        val saved = taskRepo.save(task)
        
        // Generate scheduled check-ins
        generateCheckIns(saved)

        // Notify patient: task assigned
        val notif = Notification(
            patient = patient,
            doctor = null,
            title = "New task assigned",
            message = "Your doctor assigned you a task: ${saved.taskName}",
            type = Notification.Type.TASK_ASSIGNED,
            appointmentId = null,
            documentAccessRequestId = null,
            taskId = saved.id
        )
        val savedNotif = notificationRepo.save(notif)
        patient.fcmToken?.let { fcmService.sendPatientNotification(it, savedNotif) }

        return toTaskDto(saved)
    }

    @GetMapping
    @Transactional(readOnly = true)
    fun getTasks(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestParam(required = false) patientId: Long?,
        @RequestParam(required = false) status: String?
    ): List<TaskDto> {
        requireDoctorTaskAccess(principal)
        val doctor = principal.profile
        
        val tasks = when {
            patientId != null && status != null -> {
                taskRepo.findByPatientIdAndStatus(patientId, RemoteCareTask.Status.valueOf(status.uppercase()))
                    .filter { it.doctor.id == doctor.id }
            }
            patientId != null -> {
                taskRepo.findByPatientIdOrderByCreatedAtDesc(patientId)
                    .filter { it.doctor.id == doctor.id }
            }
            status != null -> {
                taskRepo.findByDoctorIdAndStatus(doctor.id!!, RemoteCareTask.Status.valueOf(status.uppercase()))
            }
            else -> {
                taskRepo.findByDoctorIdOrderByCreatedAtDesc(doctor.id!!)
            }
        }

        return tasks.map { toTaskDto(it) }
    }

    @GetMapping("/templates")
    @Transactional(readOnly = true)
    fun getTemplates(
        @AuthenticationPrincipal principal: DoctorPrincipal
    ): List<TemplateDto> {
        requireDoctorTaskAccess(principal)
        val doctor = principal.profile
        val tasks = taskRepo.findByDoctorIdOrderByCreatedAtDesc(doctor.id!!)
        val seenNames = mutableSetOf<String>()
        return tasks.mapNotNull { task ->
            val nameKey = task.taskName.trim().lowercase()
            if (seenNames.add(nameKey)) toTemplateDto(task) else null
        }
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    fun getTask(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable id: Long
    ): TaskDto {
        requireDoctorTaskAccess(principal)
        val task = taskRepo.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found") }
        
        if (task.doctor.id != principal.profile.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }

        return toTaskDto(task)
    }

    @GetMapping("/{id}/progress")
    @Transactional(readOnly = true)
    fun getTaskProgress(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable id: Long
    ): TaskProgressDto {
        requireDoctorTaskAccess(principal)
        val task = taskRepo.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found") }
        if (task.doctor.id != principal.profile.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }
        val checkIns = checkInRepo.findByTaskIdOrderByScheduledDateAscScheduledTimeAsc(id)
        return computeProgress(checkIns, patientTimeZone(task))
    }

    @GetMapping("/{id}/check-ins")
    @Transactional(readOnly = true)
    fun getCheckIns(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable id: Long
    ): List<CheckInDto> {
        requireDoctorTaskAccess(principal)
        val task = taskRepo.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found") }
        
        if (task.doctor.id != principal.profile.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }

        return checkInRepo.findByTaskIdOrderByScheduledDateAscScheduledTimeAsc(id)
            .map { toCheckInDto(it) }
    }

    @PatchMapping("/{id}/cancel")
    fun cancelTask(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable id: Long
    ): TaskDto {
        requireDoctorTaskAccess(principal)
        val task = taskRepo.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found") }
        
        if (task.doctor.id != principal.profile.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }

        task.status = RemoteCareTask.Status.CANCELLED
        task.updatedAt = Instant.now()
        val saved = taskRepo.save(task)

        // Notify patient: task removed/cancelled
        val notif = Notification(
            patient = task.patient,
            doctor = null,
            title = "Task removed",
            message = "Your doctor removed the task: ${task.taskName}",
            type = Notification.Type.TASK_CANCELLED,
            appointmentId = null,
            documentAccessRequestId = null,
            taskId = saved.id
        )
        val savedNotif = notificationRepo.save(notif)
        task.patient.fcmToken?.let { fcmService.sendPatientNotification(it, savedNotif) }

        return toTaskDto(saved)
    }

    // ==================== PATIENT ENDPOINTS ====================

    private fun currentPatientProfile(principal: PatientPrincipal): PatientProfile {
        val user = principal.user
        return user.phone?.let { patientRepo.findByPhone(it) }
            ?.orElseGet {
                user.email?.let { patientRepo.findByEmail(it) }
                    ?.orElse(null)
            }
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Patient profile not found for user ${user.id}"
            )
    }

    @GetMapping("/my-tasks")
    @Transactional(readOnly = true)
    fun getMyTasks(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @RequestParam(required = false) status: String?
    ): List<TaskDto> {
        val patient = currentPatientProfile(principal)

        val tasks = if (status != null) {
            val parsed = RemoteCareTask.Status.valueOf(status.uppercase())
            if (parsed == RemoteCareTask.Status.COMPLETED) {
                // Completed tab: include EXPIRED tasks the patient finished or that ended.
                taskRepo.findByPatientIdOrderByCreatedAtDesc(patient.id!!)
                    .filter { it.status == RemoteCareTask.Status.COMPLETED || it.status == RemoteCareTask.Status.EXPIRED }
            } else {
                taskRepo.findByPatientIdAndStatus(patient.id!!, parsed)
            }
        } else {
            // Default list: every task the patient should see (matches detail/check-in access).
            taskRepo.findByPatientIdOrderByCreatedAtDesc(patient.id!!)
                .filter { it.status != RemoteCareTask.Status.CANCELLED }
        }

        return tasks.map { toTaskDto(it) }
    }

    @GetMapping("/my-tasks/{id}")
    @Transactional(readOnly = true)
    fun getMyTask(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @PathVariable id: Long
    ): TaskDto {
        val patient = currentPatientProfile(principal)
        val task = taskRepo.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found") }
        
        if (task.patient.id != patient.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }

        return toTaskDto(task)
    }

    data class SubmitCheckInRequest(
        val checkInId: Long,
        val numericValue: Double?,
        val textValue: String?,
        val booleanValue: Boolean?,
        val notes: String?
    )

    @PostMapping("/my-tasks/{taskId}/check-in")
    fun submitCheckIn(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @PathVariable taskId: Long,
        @RequestBody req: SubmitCheckInRequest
    ): CheckInDto {
        val patient = currentPatientProfile(principal)
        val task = taskRepo.findById(taskId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found") }
        
        if (task.patient.id != patient.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }

        val checkIn = checkInRepo.findById(req.checkInId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Check-in not found") }

        if (checkIn.task.id != task.id) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Check-in does not belong to this task")
        }

        if (checkIn.status != TaskCheckIn.Status.PENDING) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Check-in already completed or missed")
        }

        // Set values based on input type
        when (task.inputType) {
            RemoteCareTask.InputType.NUMERIC -> {
                checkIn.numericValue = req.numericValue
                    ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Numeric value required")
            }
            RemoteCareTask.InputType.TEXT -> {
                checkIn.textValue = req.textValue
                    ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Text value required")
            }
            RemoteCareTask.InputType.BOOLEAN -> {
                checkIn.booleanValue = req.booleanValue
                    ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Boolean value required")
            }
        }

        checkIn.notes = req.notes
        checkIn.markAsCompleted()
        
        val saved = checkInRepo.save(checkIn)

        // Check if all check-ins are completed
        val allCheckIns = checkInRepo.findByTaskIdOrderByScheduledDateAscScheduledTimeAsc(taskId)
        val allCompleted = allCheckIns.all { it.status == TaskCheckIn.Status.COMPLETED }
        
        if (allCompleted && task.status == RemoteCareTask.Status.ACTIVE) {
            task.markAsCompleted()
            taskRepo.save(task)
            
            // Notify doctor only (patient completed the task; do not notify the patient).
            val notification = Notification(
                patient = null,
                doctor = task.doctor,
                title = "Task Completed",
                message = "Patient ${patient.fullName} completed task: ${task.taskName}",
                type = Notification.Type.TASK_COMPLETED,
                taskId = task.id
            )
            val savedNotif = notificationRepo.save(notification)
            task.doctor.fcmToken?.let { fcmService.sendDoctorNotification(it, savedNotif) }
        }

        return toCheckInDto(saved)
    }

    @GetMapping("/my-tasks/{taskId}/check-ins")
    fun getMyCheckIns(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @PathVariable taskId: Long
    ): List<CheckInDto> {
        val patient = currentPatientProfile(principal)
        val task = taskRepo.findById(taskId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found") }
        
        if (task.patient.id != patient.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }

        return checkInRepo.findByTaskIdOrderByScheduledDateAscScheduledTimeAsc(taskId)
            .map { toCheckInDto(it) }
    }

    // ==================== HELPERS ====================

    /**
     * Parse, validate and normalize a list of HH:mm slot times provided by
     * the doctor. Returns a sorted, de-duplicated list of [LocalTime] when
     * the input is non-null and non-empty. Returns null when the doctor
     * isn't using custom-times mode (so the caller falls back to
     * startTime/intervalHours-based scheduling).
     *
     * Throws a 400 ResponseStatusException for invalid HH:mm strings or for
     * lists exceeding the maximum slot count (sanity cap of 96 = every 15
     * minutes for a full day). Empty lists collapse to null.
     */
    private fun parseCustomTimes(raw: List<String>?): List<LocalTime>? {
        if (raw == null) return null
        val cleaned = raw.mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }
        if (cleaned.isEmpty()) return null
        if (cleaned.size > 96) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many custom times (max 96)")
        }
        val parsed = cleaned.map { entry ->
            try {
                LocalTime.parse(entry)
            } catch (e: Exception) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid time '$entry' (expected HH:mm)")
            }
        }
        return parsed.distinct().sorted()
    }

    /** Convert a [LocalTime] to wire-format HH:mm. */
    private fun fmtTime(t: LocalTime): String =
        "%02d:%02d".format(t.hour, t.minute)

    /** Parse the entity's stored comma-separated `customTimes` back into
     * a list of [LocalTime]. Returns an empty list when the field is null
     * or blank. */
    private fun parseStoredCustomTimes(stored: String?): List<LocalTime> {
        val s = stored?.trim().orEmpty()
        if (s.isEmpty()) return emptyList()
        return s.split(',')
            .mapNotNull { it.trim().takeIf { v -> v.isNotEmpty() } }
            .mapNotNull {
                try {
                    LocalTime.parse(it)
                } catch (_: Exception) {
                    null
                }
            }
            .distinct()
            .sorted()
    }

    private fun generateCheckIns(task: RemoteCareTask) {
        val startDate = task.startDate
        val endDate = task.endDate ?: startDate.plusDays((task.durationDays ?: 30).toLong())
        var currentDate = startDate

        // Slot generation has three modes (in priority order):
        //   1. Custom times mode  — explicit list of HH:mm entries used verbatim.
        //   2. Even-spacing mode  — startTime + intervalHours, capped by timesPerDay
        //      and by midnight on the same day (so common pharmacy schedules
        //      like "every 6h × 4" or "every 8h × 3" produce the expected count).
        //   3. Legacy mode        — morning/afternoon/evening bucket fields.
        val maxMinExclusive = 24 * 60 // hard same-day cap
        val storedCustomTimes = parseStoredCustomTimes(task.customTimes)
        val times: List<LocalTime> = if (storedCustomTimes.isNotEmpty()) {
            storedCustomTimes
        } else if (task.startTime != null) {
            val start = task.startTime!!
            val startMin = start.toSecondOfDay() / 60
            if (task.timesPerDay <= 1) {
                listOf(start)
            } else {
                val intervalH = (task.intervalHours ?: 1).coerceIn(1, 24)
                val intervalMinutes = intervalH * 60
                buildList {
                    var min = startMin
                    var count = 0
                    while (count < task.timesPerDay && min < maxMinExclusive) {
                        add(LocalTime.of(min / 60, min % 60))
                        min += intervalMinutes
                        count++
                    }
                }
            }
        } else {
            // Legacy: morning/afternoon/evening
            buildList {
                if (task.morningTime != null) add(task.morningTime!!)
                if (task.afternoonTime != null) add(task.afternoonTime!!)
                if (task.eveningTime != null) add(task.eveningTime!!)
            }
        }

        while (!currentDate.isAfter(endDate)) {
            if (times.isEmpty()) {
                val checkIn = TaskCheckIn(
                    task = task,
                    scheduledDate = currentDate,
                    scheduledTime = null,
                    status = TaskCheckIn.Status.PENDING
                )
                task.addCheckIn(checkIn)
                checkInRepo.save(checkIn)
            } else {
                for (time in times) {
                    val checkIn = TaskCheckIn(
                        task = task,
                        scheduledDate = currentDate,
                        scheduledTime = time,
                        status = TaskCheckIn.Status.PENDING
                    )
                    task.addCheckIn(checkIn)
                    checkInRepo.save(checkIn)
                }
            }
            currentDate = currentDate.plusDays(1)
        }
    }

    /** Patient timezone for this task (schedule is in patient's time). Null → UTC. */
    private fun patientTimeZone(task: RemoteCareTask): String =
        task.patient.timeZone?.takeIf { it.isNotBlank() } ?: "UTC"

    /** Effective status: PENDING with scheduled time in the past counts as MISSED (in patient TZ). */
    private fun effectiveStatus(checkIn: TaskCheckIn, now: LocalDateTime): TaskCheckIn.Status {
        if (checkIn.status == TaskCheckIn.Status.COMPLETED) return TaskCheckIn.Status.COMPLETED
        if (checkIn.status == TaskCheckIn.Status.MISSED) return TaskCheckIn.Status.MISSED
        // PENDING: if scheduled moment has passed, treat as MISSED
        val scheduledEnd = LocalDateTime.of(
            checkIn.scheduledDate,
            checkIn.scheduledTime ?: LocalTime.MAX
        )
        return if (now.isAfter(scheduledEnd)) TaskCheckIn.Status.MISSED else TaskCheckIn.Status.PENDING
    }

    /** Schedule is in patient's timezone; use it for "now" when deciding PENDING vs MISSED. */
    private fun computeProgress(checkIns: List<TaskCheckIn>, patientTimeZone: String): TaskProgressDto {
        val zone = ZoneId.of(patientTimeZone)
        val now = java.time.ZonedDateTime.now(zone).toLocalDateTime()
        val total = checkIns.size
        var completed = 0
        var missed = 0
        for (c in checkIns) {
            when (effectiveStatus(c, now)) {
                TaskCheckIn.Status.COMPLETED -> completed++
                TaskCheckIn.Status.MISSED -> missed++
                TaskCheckIn.Status.PENDING -> { /* pending */ }
            }
        }
        val pending = total - completed - missed
        return TaskProgressDto(
            totalCheckIns = total,
            completedCheckIns = completed,
            pendingCheckIns = pending,
            missedCheckIns = missed
        )
    }

    private fun toTaskDto(task: RemoteCareTask): TaskDto {
        val checkIns = checkInRepo.findByTaskIdOrderByScheduledDateAscScheduledTimeAsc(task.id)
        val progress = computeProgress(checkIns, patientTimeZone(task))

        return TaskDto(
            id = task.id,
            patientId = task.patient.id!!,
            patientName = task.patient.fullName,
            patientTimeZone = task.patient.timeZone?.takeIf { it.isNotBlank() },
            taskName = task.taskName,
            description = task.description,
            category = task.category.name,
            status = task.status.name,
            timesPerDay = task.timesPerDay,
            startTime = task.startTime?.toString(),
            intervalHours = task.intervalHours,
            customTimes = parseStoredCustomTimes(task.customTimes)
                .map(::fmtTime)
                .takeIf { it.isNotEmpty() },
            morningTime = task.morningTime?.toString(),
            afternoonTime = task.afternoonTime?.toString(),
            eveningTime = task.eveningTime?.toString(),
            startDate = task.startDate.toString(),
            endDate = task.endDate?.toString(),
            durationDays = task.durationDays,
            inputType = task.inputType.name,
            inputLabel = task.inputLabel,
            notesRequired = task.notesRequired,
            notesLabel = task.notesLabel,
            createdAt = task.createdAt.toString(),
            progress = progress
        )
    }

    private fun toTemplateDto(task: RemoteCareTask): TemplateDto {
        return TemplateDto(
            taskName = task.taskName,
            description = task.description,
            category = task.category.name,
            timesPerDay = task.timesPerDay,
            startTime = task.startTime?.toString(),
            intervalHours = task.intervalHours,
            customTimes = parseStoredCustomTimes(task.customTimes)
                .map(::fmtTime)
                .takeIf { it.isNotEmpty() },
            morningTime = task.morningTime?.toString(),
            afternoonTime = task.afternoonTime?.toString(),
            eveningTime = task.eveningTime?.toString(),
            inputType = task.inputType.name,
            inputLabel = task.inputLabel,
            notesRequired = task.notesRequired,
            notesLabel = task.notesLabel
        )
    }

    private fun toCheckInDto(checkIn: TaskCheckIn): CheckInDto {
        return CheckInDto(
            id = checkIn.id,
            scheduledDate = checkIn.scheduledDate.toString(),
            scheduledTime = checkIn.scheduledTime?.toString(),
            status = checkIn.status.name,
            numericValue = checkIn.numericValue,
            textValue = checkIn.textValue,
            booleanValue = checkIn.booleanValue,
            notes = checkIn.notes,
            completedAt = checkIn.completedAt?.toString()
        )
    }
}
