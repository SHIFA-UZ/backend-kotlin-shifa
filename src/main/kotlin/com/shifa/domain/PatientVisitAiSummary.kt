package com.shifa.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "patient_visit_ai_summaries")
class PatientVisitAiSummary(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "appointment_id", nullable = false)
    var appointmentId: Long,

    @Column(name = "patient_id", nullable = false)
    var patientId: Long,

    @Column(name = "language", nullable = false, length = 8)
    var language: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: Status = Status.QUEUED,

    @Column(name = "content_json", columnDefinition = "TEXT")
    var contentJson: String? = null,

    @Column(name = "source_hash", length = 128)
    var sourceHash: String? = null,

    @Column(name = "model_version", nullable = false, length = 64)
    var modelVersion: String,

    @Column(name = "generated_at")
    var generatedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    enum class Status { QUEUED, READY, FAILED }
}

