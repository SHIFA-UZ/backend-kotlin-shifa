package com.shifa.domain

import jakarta.persistence.*
import java.time.LocalDate
import java.time.LocalTime

/**
 * Date-specific schedule rule that applies to a specific date or date range.
 * Used for expanding existing schedules (e.g., adding 5PM-11PM to an existing 8AM-5PM schedule).
 * 
 * Unlike WeeklyScheduleRule which is recurring, this applies only to specific dates.
 */
@Entity
@Table(name = "date_specific_schedule_rules")
class DateSpecificScheduleRule(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne
    @JoinColumn(name = "doctor_id", nullable = false)
    val doctor: DoctorProfile,

    /**
     * Optional location this expansion belongs to. When null, the expansion is treated as
     * tied to the doctor's primary location. See [DoctorLocation].
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    var location: DoctorLocation? = null,

    @Column(name = "start_date", nullable = false)
    var startDate: LocalDate, // First date this rule applies to

    @Column(name = "end_date", nullable = false)
    var endDate: LocalDate, // Last date this rule applies to (inclusive)

    @Column(name = "start_time", nullable = false)
    var startTime: LocalTime, // Start time for each day in the range

    @Column(name = "end_time", nullable = false)
    var endTime: LocalTime, // End time for each day in the range

    @Column(name = "slot_minutes", nullable = false)
    var slotMinutes: Int // Duration of each slot in minutes
) {
    /**
     * Checks if this rule applies to a specific date.
     */
    fun appliesTo(date: LocalDate): Boolean {
        return !date.isBefore(startDate) && !date.isAfter(endDate)
    }
}
