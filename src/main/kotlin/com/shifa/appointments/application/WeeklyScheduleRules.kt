package com.shifa.appointments.application

import com.shifa.appointments.domain.WeeklyScheduleRule
import com.shifa.appointments.infrastructure.WeeklyScheduleRuleRepository
import org.springframework.stereotype.Service

/**
 * The feature's door to WeeklyScheduleRule records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [WeeklyScheduleRuleRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class WeeklyScheduleRules(private val repo: WeeklyScheduleRuleRepository) {
    fun findByDoctorId(doctorId: Long): List<WeeklyScheduleRule> = repo.findByDoctorId(doctorId)
    fun findByDoctorIdAndLocationId(doctorId: Long, locationId: Long): List<WeeklyScheduleRule> = repo.findByDoctorIdAndLocationId(doctorId, locationId)
    fun deleteByDoctorId(doctorId: Long): Int = repo.deleteByDoctorId(doctorId)
    fun deleteByDoctorIdAndLocationId(doctorId: Long, locationId: Long): Int = repo.deleteByDoctorIdAndLocationId(doctorId, locationId)
    fun deleteAll(entities: Iterable<WeeklyScheduleRule>) = repo.deleteAll(entities)
    fun deleteAll() = repo.deleteAll()
    fun flush() = repo.flush()
    fun save(entity: WeeklyScheduleRule): WeeklyScheduleRule = repo.save(entity)
}
