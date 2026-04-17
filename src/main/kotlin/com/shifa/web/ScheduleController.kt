// src/main/kotlin/com/shifa/web/ScheduleController.kt
package com.shifa.web

import com.shifa.domain.DateSpecificScheduleRule
import com.shifa.domain.DoctorProfile
import com.shifa.domain.ScheduleValidityPeriod
import com.shifa.domain.WeeklyScheduleRule
import com.shifa.repo.DateSpecificScheduleRuleRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.ScheduleValidityPeriodRepository
import com.shifa.repo.WeeklyScheduleRuleRepository
import com.shifa.security.DoctorPrincipal
import com.shifa.service.ScheduleValidityService
import org.slf4j.LoggerFactory
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RestController
@RequestMapping("/api/schedule")
class ScheduleController(
    private val doctors: DoctorProfileRepository,
    private val rulesRepo: WeeklyScheduleRuleRepository,
    private val dateSpecificRulesRepo: DateSpecificScheduleRuleRepository,
    private val periodRepo: ScheduleValidityPeriodRepository,
    private val scheduleValidityService: ScheduleValidityService
) {

    companion object {
        private val logger = LoggerFactory.getLogger(ScheduleController::class.java)
    }

    data class RuleDto(
        val weekday: Int,
        val startTime: String,
        val endTime: String,
        val slotMinutes: Int
    )

    data class ValidUntilDto(
        val validUntil: String
    )

    /** Request body for schedule validity range: from when until when (adds a new period) */
    data class ValidRangeDto(
        val validFrom: String? = null,
        val validUntil: String
    )

    /** One validity period in the list response */
    data class ValidityPeriodDto(
        val validFrom: String,
        val validUntil: String
    )

    @GetMapping("/rules")
    fun rules(
        @AuthenticationPrincipal principal: DoctorPrincipal
    ): List<RuleDto> {
        val d: DoctorProfile = principal.profile
        return rulesRepo.findByDoctorId(d.id).map {
            RuleDto(
                weekday = it.weekday,
                startTime = it.startTime.toString(),
                endTime = it.endTime.toString(),
                slotMinutes = it.slotMinutes
            )
        }
    }

    @PutMapping("/rules")
    fun upsertRules(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody body: List<RuleDto>
    ): List<RuleDto> {

        val d: DoctorProfile = principal.profile
        
        // Validate rules before saving
        validateScheduleRules(body)
        
        rulesRepo.deleteAll(rulesRepo.findByDoctorId(d.id))

        val saved = body.map {
            rulesRepo.save(
                WeeklyScheduleRule(
                    doctor = d,
                    weekday = it.weekday,
                    startTime = LocalTime.parse(it.startTime),
                    endTime = LocalTime.parse(it.endTime),
                    slotMinutes = it.slotMinutes
                )
            )
        }

        return saved.map {
            RuleDto(
                weekday = it.weekday,
                startTime = it.startTime.toString(),
                endTime = it.endTime.toString(),
                slotMinutes = it.slotMinutes
            )
        }
    }
    
    /**
     * Validates schedule rules to prevent:
     * 1. Overlapping rules for the same weekday
     * 2. Invalid time ranges (start >= end)
     * 3. Extremely small slot minutes (< 5 minutes)
     * 4. Rules that would generate too many slots (> 200 per day)
     */
    private fun validateScheduleRules(rules: List<RuleDto>) {
        // Group rules by weekday
        val rulesByWeekday = rules.groupBy { it.weekday }
        
        for ((weekday, weekdayRules) in rulesByWeekday) {
            // Sort by start time
            val sorted = weekdayRules.sortedBy { LocalTime.parse(it.startTime) }
            
            for (i in sorted.indices) {
                val rule = sorted[i]
                val startTime = LocalTime.parse(rule.startTime)
                val endTime = LocalTime.parse(rule.endTime)
                
                // Validate time range
                if (startTime >= endTime) {
                    throw IllegalArgumentException(
                        "Invalid schedule rule for weekday $weekday: start time ($startTime) must be before end time ($endTime)"
                    )
                }
                
                // Validate slot minutes (minimum 5 minutes to prevent memory issues)
                if (rule.slotMinutes < 5) {
                    throw IllegalArgumentException(
                        "Invalid schedule rule for weekday $weekday: slot minutes must be at least 5 (got ${rule.slotMinutes})"
                    )
                }
                
                // Check for overlaps with other rules for the same weekday
                for (j in (i + 1) until sorted.size) {
                    val otherRule = sorted[j]
                    val otherStartTime = LocalTime.parse(otherRule.startTime)
                    val otherEndTime = LocalTime.parse(otherRule.endTime)
                    
                    // Check if rules overlap
                    if (startTime < otherEndTime && otherStartTime < endTime) {
                        throw IllegalArgumentException(
                            "Overlapping schedule rules detected for weekday $weekday: " +
                            "Rule 1: $startTime-$endTime, Rule 2: $otherStartTime-$otherEndTime. " +
                            "Please merge overlapping rules or remove duplicates."
                        )
                    }
                }
                
                // Check if this rule would generate too many slots
                val durationMinutes = java.time.Duration.between(startTime, endTime).toMinutes()
                val estimatedSlots = (durationMinutes / rule.slotMinutes).toInt()
                if (estimatedSlots > 200) {
                    throw IllegalArgumentException(
                        "Schedule rule for weekday $weekday would generate $estimatedSlots slots " +
                        "(maximum 200 allowed). Please increase slot minutes or reduce time range."
                    )
                }
            }
        }
    }

    @PatchMapping("/valid-until")
    fun setValidUntil(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody body: ValidUntilDto
    ): ValidUntilDto {
        val d: DoctorProfile = principal.profile
        d.scheduleValidUntil = LocalDate.parse(body.validUntil)
        doctors.save(d)
        return body
    }

    /**
     * GET /api/schedule/validity-periods — list all validity periods for the doctor.
     * Used by the app to show multiple calendar ranges and to add new ones without overlapping.
     */
    @GetMapping("/validity-periods")
    fun getValidityPeriods(
        @AuthenticationPrincipal principal: DoctorPrincipal
    ): List<ValidityPeriodDto> {
        val d: DoctorProfile = principal.profile
        return scheduleValidityService.getPeriods(d).map {
            ValidityPeriodDto(validFrom = it.validFrom.toString(), validUntil = it.validUntil.toString())
        }
    }

    /**
     * PATCH /api/schedule/valid-range — add a new validity period.
     *
     * The new period must NOT overlap any existing period (new start/end must be entirely
     * before or entirely after each existing period). Multiple disjoint periods are allowed
     * (e.g. 1 Mar–31 Mar and 8 Apr–30 Apr).
     */
    @PatchMapping("/valid-range")
    fun addValidRange(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody body: ValidRangeDto
    ): ValidRangeDto {
        val d: DoctorProfile = principal.profile

        val newValidFrom = body.validFrom?.let { LocalDate.parse(it) } ?: LocalDate.parse(body.validUntil)
        val newValidUntil = LocalDate.parse(body.validUntil)

        logger.info("Received calendar valid-range request: startDate={}, endDate={}", newValidFrom, newValidUntil)

        if (newValidFrom.isAfter(newValidUntil)) {
            throw IllegalArgumentException(
                "Schedule start date ($newValidFrom) must be before or equal to end date ($newValidUntil)"
            )
        }

        val existingPeriods = scheduleValidityService.getPeriods(d)
        validateNewPeriodDoesNotOverlapAny(existingPeriods, newValidFrom, newValidUntil)

        periodRepo.save(
            ScheduleValidityPeriod(doctor = d, validFrom = newValidFrom, validUntil = newValidUntil)
        )

        logger.info("Added calendar period: validFrom={}, validUntil={}", newValidFrom, newValidUntil)

        return ValidRangeDto(
            validFrom = newValidFrom.toString(),
            validUntil = newValidUntil.toString()
        )
    }

    /**
     * Validates that [newStart, newEnd] does not overlap any existing period.
     * Overlap means: newStart < existing.validUntil && newEnd > existing.validFrom.
     */
    private fun validateNewPeriodDoesNotOverlapAny(
        existingPeriods: List<ScheduleValidityPeriod>,
        newStart: LocalDate,
        newEnd: LocalDate
    ) {
        for (p in existingPeriods) {
            val overlaps = newStart.isBefore(p.validUntil) && newEnd.isAfter(p.validFrom)
            if (overlaps) {
                throw IllegalArgumentException(
                    "New schedule period ($newStart – $newEnd) overlaps existing period (${p.validFrom} – ${p.validUntil}). " +
                    "A new period must not overlap any existing period. " +
                    "Define a period entirely before or entirely after each existing one."
                )
            }
        }
    }

    // ==================== DATE-SPECIFIC SCHEDULES ====================

    data class DateSpecificRuleDto(
        val id: Long? = null,
        val startDate: String, // YYYY-MM-DD
        val endDate: String, // YYYY-MM-DD
        val startTime: String, // HH:mm
        val endTime: String, // HH:mm
        val slotMinutes: Int
    )

    /**
     * GET /api/schedule/date-specific
     * Get all date-specific schedule rules for the doctor.
     */
    @GetMapping("/date-specific")
    fun getDateSpecificRules(
        @AuthenticationPrincipal principal: DoctorPrincipal
    ): List<DateSpecificRuleDto> {
        val d: DoctorProfile = principal.profile
        return dateSpecificRulesRepo.findByDoctorId(d.id).map {
            DateSpecificRuleDto(
                id = it.id,
                startDate = it.startDate.toString(),
                endDate = it.endDate.toString(),
                startTime = it.startTime.toString(),
                endTime = it.endTime.toString(),
                slotMinutes = it.slotMinutes
            )
        }
    }

    /**
     * POST /api/schedule/date-specific
     * Create a new date-specific schedule rule.
     * 
     * Validates that:
     * 1. The new rule doesn't override existing schedules (can only expand after existing end time)
     * 2. No overlapping rules exist for the same date range
     * 3. Time range is valid
     * 4. Slot minutes is at least 5
     */
    @PostMapping("/date-specific")
    fun createDateSpecificRule(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody body: DateSpecificRuleDto
    ): DateSpecificRuleDto {
        val d: DoctorProfile = principal.profile
        
        val startDate = LocalDate.parse(body.startDate)
        val endDate = LocalDate.parse(body.endDate)
        val startTime = LocalTime.parse(body.startTime)
        val endTime = LocalTime.parse(body.endTime)
        
        // Reject past dates (use doctor's timezone so "today" is consistent)
        val zone = try { ZoneId.of(d.timeZone) } catch (_: Exception) { ZoneId.systemDefault() }
        val todayInDoctorZone = LocalDate.now(zone)
        if (startDate.isBefore(todayInDoctorZone)) {
            throw IllegalArgumentException(
                "Cannot add schedule for past dates. Start date $startDate is before today ($todayInDoctorZone in your timezone)."
            )
        }
        
        // Validate basic constraints
        validateDateSpecificRule(startDate, endDate, startTime, endTime, body.slotMinutes)
        
        // Check for conflicts with existing schedules (both weekly and date-specific)
        validateNoOverrides(d.id, startDate, endDate, startTime, endTime)
        
        val saved = dateSpecificRulesRepo.save(
            DateSpecificScheduleRule(
                doctor = d,
                startDate = startDate,
                endDate = endDate,
                startTime = startTime,
                endTime = endTime,
                slotMinutes = body.slotMinutes
            )
        )
        
        return DateSpecificRuleDto(
            id = saved.id,
            startDate = saved.startDate.toString(),
            endDate = saved.endDate.toString(),
            startTime = saved.startTime.toString(),
            endTime = saved.endTime.toString(),
            slotMinutes = saved.slotMinutes
        )
    }

    /**
     * PUT /api/schedule/date-specific/{id}
     * Update an existing date-specific schedule rule.
     */
    @PutMapping("/date-specific/{id}")
    fun updateDateSpecificRule(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable id: Long,
        @RequestBody body: DateSpecificRuleDto
    ): DateSpecificRuleDto {
        val d: DoctorProfile = principal.profile
        
        val existing = dateSpecificRulesRepo.findById(id)
            .orElseThrow { IllegalArgumentException("Date-specific schedule rule not found") }
        
        if (existing.doctor.id != d.id) {
            throw IllegalArgumentException("You can only update your own schedule rules")
        }
        
        val startDate = LocalDate.parse(body.startDate)
        val endDate = LocalDate.parse(body.endDate)
        val startTime = LocalTime.parse(body.startTime)
        val endTime = LocalTime.parse(body.endTime)
        
        // Reject past dates (use doctor's timezone)
        val zone = try { ZoneId.of(d.timeZone) } catch (_: Exception) { ZoneId.systemDefault() }
        val todayInDoctorZone = LocalDate.now(zone)
        if (startDate.isBefore(todayInDoctorZone)) {
            throw IllegalArgumentException(
                "Cannot add schedule for past dates. Start date $startDate is before today ($todayInDoctorZone in your timezone)."
            )
        }
        
        // Validate basic constraints
        validateDateSpecificRule(startDate, endDate, startTime, endTime, body.slotMinutes)
        
        // Check for conflicts with existing schedules (excluding the current rule being updated)
        validateNoOverrides(d.id, startDate, endDate, startTime, endTime, excludeRuleId = id)
        
        existing.startDate = startDate
        existing.endDate = endDate
        existing.startTime = startTime
        existing.endTime = endTime
        existing.slotMinutes = body.slotMinutes
        
        val saved = dateSpecificRulesRepo.save(existing)
        
        return DateSpecificRuleDto(
            id = saved.id,
            startDate = saved.startDate.toString(),
            endDate = saved.endDate.toString(),
            startTime = saved.startTime.toString(),
            endTime = saved.endTime.toString(),
            slotMinutes = saved.slotMinutes
        )
    }

    /**
     * DELETE /api/schedule/date-specific/{id}
     * Delete a date-specific schedule rule.
     */
    @DeleteMapping("/date-specific/{id}")
    fun deleteDateSpecificRule(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable id: Long
    ) {
        val d: DoctorProfile = principal.profile
        
        val existing = dateSpecificRulesRepo.findById(id)
            .orElseThrow { IllegalArgumentException("Date-specific schedule rule not found") }
        
        if (existing.doctor.id != d.id) {
            throw IllegalArgumentException("You can only delete your own schedule rules")
        }
        
        dateSpecificRulesRepo.delete(existing)
    }

    /**
     * Validates basic constraints for a date-specific rule.
     */
    private fun validateDateSpecificRule(
        startDate: LocalDate,
        endDate: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime,
        slotMinutes: Int
    ) {
        if (startDate.isAfter(endDate)) {
            throw IllegalArgumentException("Start date must be before or equal to end date")
        }
        
        if (startTime >= endTime) {
            throw IllegalArgumentException("Start time must be before end time")
        }
        
        if (slotMinutes < 5) {
            throw IllegalArgumentException("Slot minutes must be at least 5 (got $slotMinutes)")
        }
        
        // Check if this rule would generate too many slots
        val durationMinutes = java.time.Duration.between(startTime, endTime).toMinutes()
        val estimatedSlots = (durationMinutes / slotMinutes).toInt()
        if (estimatedSlots > 200) {
            throw IllegalArgumentException(
                "This schedule would generate $estimatedSlots slots per day " +
                "(maximum 200 allowed). Please increase slot minutes or reduce time range."
            )
        }
    }

    /**
     * Validates that a new date-specific rule doesn't override existing schedules.
     * 
     * Rules:
     * 1. Cannot overlap with existing date-specific rules for the same date range
     * 2. For dates with existing weekly rules: can only expand AFTER the existing end time
     * 3. For dates with existing date-specific rules: cannot override, can only expand after
     */
    private fun validateNoOverrides(
        doctorId: Long,
        newStartDate: LocalDate,
        newEndDate: LocalDate,
        newStartTime: LocalTime,
        newEndTime: LocalTime,
        excludeRuleId: Long? = null
    ) {
        // Check for overlapping date-specific rules
        val overlappingDateSpecific = dateSpecificRulesRepo
            .findOverlappingWithDateRange(doctorId, newStartDate, newEndDate)
            .filter { excludeRuleId == null || it.id != excludeRuleId }
        
        // For each date in the new range, check for conflicts
        var currentDate = newStartDate
        while (!currentDate.isAfter(newEndDate)) {
            // Check date-specific rules for this date
            val dateSpecificForDay = overlappingDateSpecific.filter { it.appliesTo(currentDate) }
            
            for (existingRule in dateSpecificForDay) {
                // Check if new rule overlaps with existing rule
                if (newStartTime < existingRule.endTime && existingRule.startTime < newEndTime) {
                    // Check if new rule is trying to override (starts before existing ends)
                    if (newStartTime < existingRule.endTime) {
                        // Allow only if new rule starts exactly where existing ends (expansion)
                        if (newStartTime != existingRule.endTime) {
                            throw IllegalArgumentException(
                                "Cannot override existing schedule for ${currentDate}. " +
                                "Existing schedule: ${existingRule.startTime}-${existingRule.endTime}. " +
                                "You can only expand the schedule after ${existingRule.endTime}."
                            )
                        }
                    }
                }
            }
            
            // Check weekly rules for this date's weekday
            val weekday = currentDate.dayOfWeek.value
            val weeklyRules = rulesRepo.findByDoctorId(doctorId)
                .filter { it.weekday == weekday }
            
            for (weeklyRule in weeklyRules) {
                // Check if new rule overlaps with weekly rule
                if (newStartTime < weeklyRule.endTime && weeklyRule.startTime < newEndTime) {
                    // Check if new rule is trying to override (starts before weekly ends)
                    if (newStartTime < weeklyRule.endTime) {
                        // Allow only if new rule starts exactly where weekly ends (expansion)
                        if (newStartTime != weeklyRule.endTime) {
                            throw IllegalArgumentException(
                                "Cannot override existing weekly schedule for ${currentDate} (${getWeekdayName(weekday)}). " +
                                "Existing schedule: ${weeklyRule.startTime}-${weeklyRule.endTime}. " +
                                "You can only expand the schedule after ${weeklyRule.endTime}."
                            )
                        }
                    }
                }
            }
            
            currentDate = currentDate.plusDays(1)
        }
    }

    private fun getWeekdayName(weekday: Int): String {
        return when (weekday) {
            1 -> "Monday"
            2 -> "Tuesday"
            3 -> "Wednesday"
            4 -> "Thursday"
            5 -> "Friday"
            6 -> "Saturday"
            7 -> "Sunday"
            else -> "Day $weekday"
        }
    }
}
