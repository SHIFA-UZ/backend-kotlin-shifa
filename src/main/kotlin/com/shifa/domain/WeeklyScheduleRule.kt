package com.shifa.domain

import jakarta.persistence.*
import java.time.LocalTime

/**
 * Recurring weekly rule that the server expands into many free slots for a day.
 * Matches V2__schedule_and_profile_extensions.sql.
 *
 * If [location] is non-null, slots expanded from this rule belong to that doctor location
 * (see [DoctorLocation]); otherwise slots are treated as belonging to the doctor's primary
 * location. Cross-location overlap is rejected at the controller layer.
 */
@Entity @Table(name="weekly_schedule_rules")
class WeeklyScheduleRule(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne @JoinColumn(name="doctor_id", nullable = false)
    val doctor: DoctorProfile,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    var location: DoctorLocation? = null,

    @Column(nullable = false) val weekday: Int,   // 1..7 Mon..Sun
    @Column(name="start_time", nullable = false) val startTime: LocalTime,
    @Column(name="end_time",   nullable = false) val endTime: LocalTime,
    @Column(name="slot_minutes", nullable = false) val slotMinutes: Int
)
