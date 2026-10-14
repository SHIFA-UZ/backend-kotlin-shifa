package com.shifa.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "patient_visit_facts")
class PatientVisitFact(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "doctor_id", nullable = false)
    val doctorId: Long,

    @Column(name = "patient_id", nullable = false)
    val patientId: Long,

    @Column(name = "appointment_id")
    var appointmentId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "fact_type", nullable = false, length = 32)
    var factType: FactType,

    @Column(nullable = false, columnDefinition = "TEXT")
    var text: String,

    @Column(columnDefinition = "TEXT")
    var quote: String? = null,

    @Column(name = "schedule_hint")
    var scheduleHint: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: Status = Status.DRAFT,

    @Column(name = "remote_task_id")
    var remoteTaskId: Long? = null,

    @Column(name = "reflection_kept", nullable = false)
    var reflectionKept: Boolean = true,

    @Column(name = "drop_reason", columnDefinition = "TEXT")
    var dropReason: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "committed_at")
    var committedAt: Instant? = null,
) {
    enum class FactType {
        MEDICATION,
        DIAGNOSIS,
        THERAPY,
        SYMPTOM,
    }

    enum class Status {
        DRAFT,
        COMMITTED,
        REJECTED,
        DROPPED,
    }
}
