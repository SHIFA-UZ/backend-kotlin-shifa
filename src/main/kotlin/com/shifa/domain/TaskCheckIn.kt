package com.shifa.domain

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@Entity
@Table(name = "task_check_ins")
class TaskCheckIn(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne
    @JoinColumn(name = "task_id", nullable = false)
    var task: RemoteCareTask,

    @Column(name = "scheduled_date", nullable = false)
    val scheduledDate: LocalDate,

    @Column(name = "scheduled_time")
    val scheduledTime: LocalTime? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: Status = Status.PENDING,

    // Patient input
    @Column(name = "numeric_value")
    var numericValue: Double? = null,

    @Column(name = "text_value", columnDefinition = "TEXT")
    var textValue: String? = null,

    @Column(name = "boolean_value")
    var booleanValue: Boolean? = null,

    @Column(name = "notes", columnDefinition = "TEXT")
    var notes: String? = null,

    @Column(name = "completed_at")
    var completedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "reminder_sent_at")
    var reminderSentAt: Instant? = null
) {
    enum class Status {
        PENDING,
        COMPLETED,
        MISSED
    }

    fun markAsCompleted() {
        status = Status.COMPLETED
        completedAt = Instant.now()
    }

    fun markAsMissed() {
        if (status == Status.PENDING) {
            status = Status.MISSED
        }
    }
}
