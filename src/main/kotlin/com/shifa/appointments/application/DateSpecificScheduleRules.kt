package com.shifa.appointments.application

import com.shifa.appointments.domain.DateSpecificScheduleRule
import com.shifa.appointments.infrastructure.DateSpecificScheduleRuleRepository
import java.time.LocalDate
import java.util.Optional
import org.springframework.stereotype.Service

/**
 * The feature's door to DateSpecificScheduleRule records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [DateSpecificScheduleRuleRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class DateSpecificScheduleRules(private val repo: DateSpecificScheduleRuleRepository) {
    fun deleteByDoctorId(doctorId: Long): Int = repo.deleteByDoctorId(doctorId)
    fun findByDoctorId(doctorId: Long): List<DateSpecificScheduleRule> = repo.findByDoctorId(doctorId)
    fun findByDoctorIdAndLocationId(doctorId: Long, locationId: Long): List<DateSpecificScheduleRule> = repo.findByDoctorIdAndLocationId(doctorId, locationId)
    fun findByDoctorIdAndDate(doctorId: Long, date: LocalDate): List<DateSpecificScheduleRule> = repo.findByDoctorIdAndDate(doctorId, date)
    fun findOverlappingWithDateRange(doctorId: Long, rangeStartDate: LocalDate, rangeEndDate: LocalDate): List<DateSpecificScheduleRule> = repo.findOverlappingWithDateRange(doctorId, rangeStartDate, rangeEndDate)
    fun delete(entity: DateSpecificScheduleRule) = repo.delete(entity)
    fun findById(id: Long): Optional<DateSpecificScheduleRule> = repo.findById(id)
    fun save(entity: DateSpecificScheduleRule): DateSpecificScheduleRule = repo.save(entity)
}
