package com.shifa.tasks.api.dto

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
    val notesLabel: String?,
    /** The visit this task belongs to; must be an appointment of this doctor with this patient. */
    val appointmentId: Long? = null,
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
    val progress: TaskProgressDto?,
    /** The doctor who assigned the task (patient-app ask 14). */
    val doctorId: Long? = null,
    val doctorName: String? = null,
    val appointmentId: Long? = null,
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

data class SubmitCheckInRequest(
    val checkInId: Long,
    val numericValue: Double?,
    val textValue: String?,
    val booleanValue: Boolean?,
    val notes: String?
)
