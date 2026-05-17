package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(
    name = "patient_prophylaxis_settings",
    uniqueConstraints = [UniqueConstraint(columnNames = ["patient_id", "clinic_id"])]
)
class PatientProphylaxisSetting(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    val patient: PatientProfile,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clinic_id", nullable = false)
    val clinic: Clinic,

    @Column(name = "interval_months", nullable = false)
    var intervalMonths: Int = 6,

    @Column(nullable = false)
    var enabled: Boolean = true,

    @Column(name = "last_sent_at")
    var lastSentAt: OffsetDateTime? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
