package com.shifa.domain

import jakarta.persistence.*
import java.time.Instant

/**
 * Ad-hoc unavailability (e.g. emergency) that hides free slots for a doctor
 * without changing their recurring schedule rules.
 */
@Entity
@Table(name = "schedule_blocks")
class ScheduleBlock(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    val doctor: DoctorProfile,

    @Column(name = "start_at", nullable = false)
    var startAt: Instant,

    @Column(name = "end_at", nullable = false)
    var endAt: Instant,

    @Column(length = 500)
    var reason: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
)
