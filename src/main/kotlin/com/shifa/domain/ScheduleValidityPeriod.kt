package com.shifa.domain

import jakarta.persistence.*
import java.time.LocalDate

/**
 * A single validity period for a doctor's schedule (e.g. 01 Mar – 31 Mar, or 08 Apr – 30 Apr).
 * A doctor can have multiple disjoint periods; slots are valid on a date if it falls within any period.
 */
@Entity
@Table(name = "schedule_validity_periods")
class ScheduleValidityPeriod(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    val doctor: DoctorProfile,

    @Column(name = "valid_from", nullable = false)
    var validFrom: LocalDate,

    @Column(name = "valid_until", nullable = false)
    var validUntil: LocalDate,
) {
    fun contains(date: LocalDate): Boolean =
        !date.isBefore(validFrom) && !date.isAfter(validUntil)

    override fun toString(): String = "ScheduleValidityPeriod(id=$id, validFrom=$validFrom, validUntil=$validUntil)"
}
