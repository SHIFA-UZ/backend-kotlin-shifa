package com.shifa.domain

import jakarta.persistence.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant

@Entity
@Table(name = "remote_care_tasks")
class RemoteCareTask(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne
    @JoinColumn(name = "doctor_id", nullable = false)
    val doctor: DoctorProfile,

    @ManyToOne
    @JoinColumn(name = "patient_id", nullable = false)
    val patient: PatientProfile,

    @Column(nullable = false)
    var taskName: String,

    @Column(columnDefinition = "TEXT")
    var description: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var category: Category,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: Status = Status.ACTIVE,

    // Schedule configuration
    @Column(name = "times_per_day", nullable = false)
    var timesPerDay: Int = 1,

    /** First slot time of the day. Null = legacy (use morning/afternoon/evening). */
    @Column(name = "start_time")
    var startTime: LocalTime? = null,

    /** When timesPerDay >= 2: hours between consecutive slots (1–24).
     * Slots are stepped forward from startTime until the [timesPerDay] count is
     * reached or the slot would cross midnight on the same calendar day.
     * Ignored when [customTimes] is set. */
    @Column(name = "interval_hours")
    var intervalHours: Int? = null,

    /** Comma-separated list of explicit slot times in HH:mm format (e.g.
     * "08:00,10:00,12:00,17:00,22:00"). When non-null and non-empty this
     * fully drives the daily schedule — [intervalHours] is ignored and
     * [timesPerDay] is informational (kept in sync with the entry count by
     * the controller). Lets doctors define non-uniform medication
     * schedules like "every 2h for the first three doses, then every 5h
     * for the last two". */
    @Column(name = "custom_times", columnDefinition = "TEXT")
    var customTimes: String? = null,

    @Column(name = "morning_time")
    var morningTime: LocalTime? = null,

    @Column(name = "afternoon_time")
    var afternoonTime: LocalTime? = null,

    @Column(name = "evening_time")
    var eveningTime: LocalTime? = null,

    @Column(name = "start_date", nullable = false)
    var startDate: LocalDate,

    @Column(name = "end_date")
    var endDate: LocalDate? = null,

    @Column(name = "duration_days")
    var durationDays: Int? = null, // Alternative to end_date

    // Input configuration
    @Enumerated(EnumType.STRING)
    @Column(name = "input_type", nullable = false)
    var inputType: InputType,

    @Column(name = "input_label")
    var inputLabel: String? = null, // e.g., "Blood Pressure", "Weight (kg)"

    @Column(name = "notes_required")
    var notesRequired: Boolean = false,

    @Column(name = "notes_label")
    var notesLabel: String? = null, // e.g., "Additional notes"

    // Metadata
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at")
    var updatedAt: Instant? = null,

    @OneToMany(
        mappedBy = "task",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.LAZY
    )
    val checkIns: MutableList<TaskCheckIn> = mutableListOf()
) {
    enum class Category {
        VITAL,
        EXERCISE,
        MEDICATION,
        OTHER
    }

    enum class Status {
        DRAFT,
        ACTIVE,
        COMPLETED,
        EXPIRED,
        CANCELLED
    }

    enum class InputType {
        NUMERIC,
        TEXT,
        BOOLEAN
    }

    fun addCheckIn(checkIn: TaskCheckIn) {
        checkIns.add(checkIn)
        checkIn.task = this
    }

    fun removeCheckIn(checkIn: TaskCheckIn) {
        checkIns.remove(checkIn)
        // Note: With orphanRemoval = true, JPA will handle the relationship
        // We cannot set task to null as it's non-nullable in the entity
    }

    fun isExpired(): Boolean {
        val end = endDate ?: startDate.plusDays((durationDays ?: 0).toLong())
        return LocalDate.now().isAfter(end) && status == Status.ACTIVE
    }

    fun markAsCompleted() {
        if (status == Status.ACTIVE) {
            status = Status.COMPLETED
            updatedAt = Instant.now()
        }
    }
}
