package com.shifa.service

import com.shifa.domain.DoctorProfile
import com.shifa.domain.ScheduleValidityPeriod
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.ScheduleValidityPeriodRepository
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * Centralizes logic for multiple schedule validity periods per doctor.
 * A date is valid if it falls within any period. New periods must not overlap any existing one.
 */
@Service
class ScheduleValidityService(
    private val periodRepo: ScheduleValidityPeriodRepository,
    private val doctorRepo: DoctorProfileRepository
) {

    /**
     * Returns all validity periods for the doctor, ordered by validFrom.
     * If the new table is empty but the doctor has legacy scheduleValidFrom/Until, migrates them
     * into one row and returns it.
     */
    fun getPeriods(doctor: DoctorProfile): List<ScheduleValidityPeriod> {
        migrateLegacyIfNeeded(doctor)
        return periodRepo.findByDoctorIdOrderByValidFromAsc(doctor.id)
    }

    /**
     * Returns true if the given date falls within any validity period for the doctor.
     */
    fun isDateWithinAnyPeriod(doctor: DoctorProfile, date: LocalDate): Boolean {
        return getPeriods(doctor).any { it.contains(date) }
    }

    /**
     * If the doctor has no rows in schedule_validity_periods but has legacy scheduleValidFrom/Until,
     * insert one period and clear the legacy columns.
     */
    fun migrateLegacyIfNeeded(doctor: DoctorProfile) {
        if (periodRepo.findByDoctorIdOrderByValidFromAsc(doctor.id).isNotEmpty()) return
        val from = doctor.scheduleValidFrom
        val until = doctor.scheduleValidUntil ?: return
        if (from == null) return
        periodRepo.save(
            ScheduleValidityPeriod(doctor = doctor, validFrom = from, validUntil = until)
        )
        doctor.scheduleValidFrom = null
        doctor.scheduleValidUntil = null
        doctorRepo.save(doctor)
    }
}
