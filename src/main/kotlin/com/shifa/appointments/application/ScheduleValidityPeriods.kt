package com.shifa.appointments.application

import com.shifa.appointments.domain.ScheduleValidityPeriod
import com.shifa.appointments.infrastructure.ScheduleValidityPeriodRepository
import org.springframework.stereotype.Service

/**
 * The feature's door to ScheduleValidityPeriod records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [ScheduleValidityPeriodRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class ScheduleValidityPeriods(private val repo: ScheduleValidityPeriodRepository) {
    fun findByDoctorIdOrderByValidFromAsc(doctorId: Long): List<ScheduleValidityPeriod> = repo.findByDoctorIdOrderByValidFromAsc(doctorId)
    fun deleteAll(entities: Iterable<ScheduleValidityPeriod>) = repo.deleteAll(entities)
    fun deleteAll() = repo.deleteAll()
    fun save(entity: ScheduleValidityPeriod): ScheduleValidityPeriod = repo.save(entity)
}
