package com.shifa.appointments.application

import com.shifa.appointments.domain.ScheduleValidityPeriod
import com.shifa.appointments.infrastructure.ScheduleValidityPeriodRepository
import com.shifa.doctors.domain.DoctorProfile
import java.time.LocalDate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Centralizes logic for multiple schedule validity periods per doctor.
 * A date is valid if it falls within any period. New periods must not overlap any existing one.
 */
@Service
class ScheduleValidityService(
    private val periodRepo: ScheduleValidityPeriodRepository,
    private val doctorRepo: com.shifa.doctors.application.DoctorProfiles
) {

    /**
     * Returns all validity periods for the doctor, ordered by validFrom.
     * If the new table is empty but the doctor has legacy scheduleValidFrom/Until, migrates them
     * into one row and returns it.
     */
    @Transactional
    fun getPeriods(doctor: DoctorProfile): List<ScheduleValidityPeriod> {
        migrateLegacyIfNeeded(doctor)
        return periodRepo.findByDoctorIdOrderByValidFromAsc(doctor.id)
    }

    /**
     * Returns true if the given date falls within any validity period for the doctor.
     */
    @Transactional
    fun isDateWithinAnyPeriod(doctor: DoctorProfile, date: LocalDate): Boolean {
        return getPeriods(doctor).any { it.contains(date) }
    }

    /**
     * If the doctor has no rows in schedule_validity_periods but has legacy scheduleValidFrom/Until,
     * insert one period and clear the legacy columns.
     */
    @Transactional
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
