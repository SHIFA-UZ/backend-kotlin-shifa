package com.shifa.appointments.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "clinic_visit_patterns")
class ClinicVisitPattern(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "doctor_id", nullable = false)
    val doctorId: Long,

    @Column(name = "pattern_key", nullable = false, length = 128)
    var patternKey: String,

    @Column(name = "pattern_label", nullable = false, columnDefinition = "TEXT")
    var patternLabel: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "fact_type", nullable = false, length = 32)
    var factType: PatientVisitFact.FactType,

    @Column(name = "occurrence_count", nullable = false)
    var occurrenceCount: Int = 0,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
