package com.shifa.service

import com.shifa.domain.DoctorProfile
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.DateSpecificScheduleRuleRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.ScheduleBlockRepository
import com.shifa.repo.ScheduleValidityPeriodRepository
import com.shifa.repo.WeeklyScheduleRuleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Admin-only service to reset a doctor's calendar: permanently delete all
 * appointments and availability (schedule rules). Does NOT touch credentials,
 * profile, or patient data.
 */
@Service
class AdminCalendarResetService(
    private val doctorProfileRepository: DoctorProfileRepository,
    private val appointmentRepository: AppointmentRepository,
    private val weeklyScheduleRuleRepository: WeeklyScheduleRuleRepository,
    private val dateSpecificScheduleRuleRepository: DateSpecificScheduleRuleRepository,
    private val scheduleBlockRepository: ScheduleBlockRepository,
    private val scheduleValidityPeriodRepository: ScheduleValidityPeriodRepository
) {
    private val log = LoggerFactory.getLogger(AdminCalendarResetService::class.java)

    /**
     * Permanently delete all calendar-related data for the doctor:
     * - All appointments (past and future)
     * - All weekly schedule rules
     * - All date-specific schedule rules
     *
     * Doctor profile, credentials, and patients are unchanged.
     * All-or-nothing: any failure rolls back the transaction.
     *
     * @param doctorId DoctorProfile.id (not User.id)
     * @return The doctor profile for logging
     * @throws NoSuchElementException if doctor not found
     */
    @Transactional
    fun resetDoctorCalendar(doctorId: Long): DoctorProfile {
        val doctor = doctorProfileRepository.findById(doctorId)
            .orElseThrow { NoSuchElementException("Doctor not found: $doctorId") }

        log.info("RESET_DOCTOR_CALENDAR: Starting calendar reset for doctorId={}, userId={}", doctorId, doctor.user.id)

        val appointmentsDeleted = appointmentRepository.deleteByDoctorId(doctorId)
        val weeklyDeleted = weeklyScheduleRuleRepository.deleteByDoctorId(doctorId)
        val dateSpecificDeleted = dateSpecificScheduleRuleRepository.deleteByDoctorId(doctorId)
        val blocksDeleted = scheduleBlockRepository.deleteByDoctorId(doctorId)

        val periods = scheduleValidityPeriodRepository.findByDoctorIdOrderByValidFromAsc(doctorId)
        scheduleValidityPeriodRepository.deleteAll(periods)

        // Clear legacy schedule validity range so the doctor can define a new calendar
        doctor.scheduleValidFrom = null
        doctor.scheduleValidUntil = null
        doctorProfileRepository.save(doctor)

        log.info(
            "RESET_DOCTOR_CALENDAR: Completed for doctorId={}. Deleted: appointments={}, weeklyRules={}, dateSpecificRules={}, blocks={}, validityPeriods={}. Cleared validFrom/validUntil.",
            doctorId, appointmentsDeleted, weeklyDeleted, dateSpecificDeleted, blocksDeleted, periods.size
        )

        return doctor
    }
}
