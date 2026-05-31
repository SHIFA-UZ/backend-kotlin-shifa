package com.shifa.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "doctor_sms_usage")
class DoctorSmsUsage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    val doctor: DoctorProfile,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id")
    val patient: PatientProfile? = null,

    @Column(name = "appointment_id")
    val appointmentId: Long? = null,

    @Column(name = "cost_minor", nullable = false)
    val costMinor: Long,

    @Column(nullable = false, length = 3)
    val currency: String = "UZS",

    @Column(name = "devsms_sms_id", length = 64)
    val devsmsSmsId: String? = null,

    @Column(name = "sent_at", nullable = false)
    val sentAt: Instant = Instant.now(),
)
