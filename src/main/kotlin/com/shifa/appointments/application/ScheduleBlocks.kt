package com.shifa.appointments.application

import com.shifa.appointments.domain.ScheduleBlock
import com.shifa.appointments.infrastructure.ScheduleBlockRepository
import java.util.Optional
import org.springframework.stereotype.Service

/**
 * The feature's door to ScheduleBlock records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [ScheduleBlockRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class ScheduleBlocks(private val repo: ScheduleBlockRepository) {
    fun deleteByDoctorId(doctorId: Long): Int = repo.deleteByDoctorId(doctorId)
    fun findOverlapping(doctorId: Long, rangeStart: java.time.Instant, rangeEnd: java.time.Instant): List<ScheduleBlock> = repo.findOverlapping(doctorId, rangeStart, rangeEnd)
    fun delete(entity: ScheduleBlock) = repo.delete(entity)
    fun findById(id: Long): Optional<ScheduleBlock> = repo.findById(id)
}
