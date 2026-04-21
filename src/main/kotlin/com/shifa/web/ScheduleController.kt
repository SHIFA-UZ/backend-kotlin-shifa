// src/main/kotlin/com/shifa/web/ScheduleController.kt
package com.shifa.web

import com.shifa.domain.DateSpecificScheduleRule
import com.shifa.domain.DoctorLocation
import com.shifa.domain.DoctorProfile
import com.shifa.domain.ScheduleValidityPeriod
import com.shifa.domain.WeeklyScheduleRule
import com.shifa.repo.DateSpecificScheduleRuleRepository
import com.shifa.repo.DoctorLocationRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.ScheduleValidityPeriodRepository
import com.shifa.repo.WeeklyScheduleRuleRepository
import com.shifa.security.DoctorPrincipal
import com.shifa.service.ScheduleValidityService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
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
    private val scheduleValidityService: ScheduleValidityService,
    private val locationRepo: DoctorLocationRepository
) {

    companion object {
        private val logger = LoggerFactory.getLogger(ScheduleController::class.java)
    }

    /**
     * One weekly recurring rule. `locationId` is optional on the wire — when omitted, the
     * doctor's primary location is used. The patient app always sees one `locationId` per slot
     * so it can show "which place" a slot belongs to.
     */
    data class RuleDto(
        val weekday: Int,
        val startTime: String,
        val endTime: String,
        val slotMinutes: Int,
        val locationId: Long? = null
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

    /**
     * GET /api/schedule/rules — optionally filtered by locationId.
     *
     * Without `locationId`, returns all rules across all locations (each with its locationId
     * so the client can group). With `locationId`, returns only rules for that location.
     */
    @GetMapping("/rules")
    @Transactional(readOnly = true)
    fun rules(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestParam(required = false) locationId: Long?
    ): List<RuleDto> {
        val d: DoctorProfile = principal.profile
        val all = rulesRepo.findByDoctorId(d.id)
        val filtered = if (locationId == null) all else all.filter { it.location?.id == locationId }
        return filtered.map {
            RuleDto(
                weekday = it.weekday,
                startTime = it.startTime.toString(),
                endTime = it.endTime.toString(),
                slotMinutes = it.slotMinutes,
                locationId = it.location?.id
            )
        }
    }

    /**
     * PUT /api/schedule/rules — replaces the doctor's weekly rules.
     *
     * If `locationId` query param is provided, only rules for that location are replaced
     * (rules for other locations are left untouched). If it's omitted, all rules across all
     * locations are replaced (legacy behavior) — every rule in the body is tied to the
     * doctor's primary location.
     *
     * Overlap validation is cross-location: a rule for Location A on Monday 10–12 will be
     * rejected if Location B already has Monday 11–13, because the doctor can't be in two
     * places at once.
     */
    @PutMapping("/rules")
    @Transactional
    fun upsertRules(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestParam(required = false) locationId: Long?,
        @RequestBody body: List<RuleDto>
    ): List<RuleDto> {
        val d: DoctorProfile = principal.profile

        // Resolve default location (primary) once, used when the client sends rules without
        // an explicit locationId (e.g. a single-location doctor or legacy clients).
        val primary = locationRepo.findByDoctorIdAndIsPrimaryTrue(d.id).orElse(null)
            ?: ensurePrimaryLocationExists(d)
        val targetLocationId = locationId

        validateWeeklyRulesWithinRequest(body)
        validateNoCrossLocationOverlap(d.id, body, targetLocationId)

        if (targetLocationId != null) {
            val targetLoc = locationRepo.findById(targetLocationId)
                .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Location not found") }
            if (targetLoc.doctor.id != d.id) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not your location")
            }
            rulesRepo.deleteByDoctorIdAndLocationId(d.id, targetLocationId)
            rulesRepo.flush()
            val saved = body.map { createWeeklyRule(d, targetLoc, it) }
            return saved.map(::toRuleDto)
        }

        // Legacy path: replace everything, pin to primary location.
        rulesRepo.deleteAll(rulesRepo.findByDoctorId(d.id))
        rulesRepo.flush()
        val saved = body.map { rule ->
            val loc = rule.locationId?.let { lid ->
                locationRepo.findById(lid).orElse(null)?.takeIf { it.doctor.id == d.id }
            } ?: primary
            createWeeklyRule(d, loc, rule)
        }
        return saved.map(::toRuleDto)
    }

    private fun createWeeklyRule(d: DoctorProfile, loc: DoctorLocation, rule: RuleDto): WeeklyScheduleRule =
        rulesRepo.save(
            WeeklyScheduleRule(
                doctor = d,
                location = loc,
                weekday = rule.weekday,
                startTime = LocalTime.parse(rule.startTime),
                endTime = LocalTime.parse(rule.endTime),
                slotMinutes = rule.slotMinutes
            )
        )

    private fun toRuleDto(it: WeeklyScheduleRule): RuleDto = RuleDto(
        weekday = it.weekday,
        startTime = it.startTime.toString(),
        endTime = it.endTime.toString(),
        slotMinutes = it.slotMinutes,
        locationId = it.location?.id
    )

    /**
     * If the doctor has no locations yet (e.g. a brand-new doctor), create one labeled "Main
     * Clinic" from their profile fields so the schedule can still be saved without forcing
     * the UI to go to the locations screen first.
     */
    private fun ensurePrimaryLocationExists(d: DoctorProfile): DoctorLocation {
        return locationRepo.save(
            DoctorLocation(
                doctor = d,
                label = d.clinic?.takeIf { it.isNotBlank() } ?: "Main Clinic",
                clinic = d.clinic,
                address = d.address,
                latitude = d.latitude,
                longitude = d.longitude,
                locationCountry = d.locationCountry,
                locationRegion = d.locationRegion,
                locationDistrict = d.locationDistrict,
                locationCity = d.locationCity,
                locationPostalCode = d.locationPostalCode,
                locationStreetAddress = d.locationStreetAddress,
                isPrimary = true
            )
        )
    }

    /**
     * Within-request validation: invalid ranges, microscopic slot sizes, and overlaps
     * within the SAME location (we still allow the same weekday to exist for multiple
     * different locations).
     */
    private fun validateWeeklyRulesWithinRequest(rules: List<RuleDto>) {
        val byLocationAndDay = rules.groupBy { (it.locationId ?: -1L) to it.weekday }
        for ((_, group) in byLocationAndDay) {
            val sorted = group.sortedBy { LocalTime.parse(it.startTime) }
            for (i in sorted.indices) {
                val rule = sorted[i]
                val startTime = LocalTime.parse(rule.startTime)
                val endTime = LocalTime.parse(rule.endTime)

                if (startTime >= endTime) {
                    throw IllegalArgumentException(
                        "Invalid schedule rule for weekday ${rule.weekday}: start time ($startTime) must be before end time ($endTime)"
                    )
                }
                if (rule.slotMinutes < 5) {
                    throw IllegalArgumentException(
                        "Invalid schedule rule for weekday ${rule.weekday}: slot minutes must be at least 5 (got ${rule.slotMinutes})"
                    )
                }

                for (j in (i + 1) until sorted.size) {
                    val other = sorted[j]
                    val otherStart = LocalTime.parse(other.startTime)
                    val otherEnd = LocalTime.parse(other.endTime)
                    if (startTime < otherEnd && otherStart < endTime) {
                        throw IllegalArgumentException(
                            "Overlapping schedule rules detected for weekday ${rule.weekday} at the same location: " +
                                    "$startTime-$endTime vs $otherStart-$otherEnd. Merge or remove duplicates."
                        )
                    }
                }

                val durationMinutes = java.time.Duration.between(startTime, endTime).toMinutes()
                val estimatedSlots = (durationMinutes / rule.slotMinutes).toInt()
                if (estimatedSlots > 200) {
                    throw IllegalArgumentException(
                        "Schedule rule for weekday ${rule.weekday} would generate $estimatedSlots slots " +
                                "(maximum 200). Increase slot minutes or reduce time range."
                    )
                }
            }
        }
    }

    /**
     * Cross-location validation: a doctor can't be at two places at once. The incoming
     * rules (for [targetLocationId] — the one being replaced) must not overlap existing
     * rules from any OTHER location on the same weekday.
     */
    private fun validateNoCrossLocationOverlap(
        doctorId: Long,
        incoming: List<RuleDto>,
        targetLocationId: Long?
    ) {
        val existing = rulesRepo.findByDoctorId(doctorId)
        for (newRule in incoming) {
            val newStart = LocalTime.parse(newRule.startTime)
            val newEnd = LocalTime.parse(newRule.endTime)
            for (oldRule in existing) {
                if (oldRule.weekday != newRule.weekday) continue
                val oldLocationId = oldRule.location?.id
                // Skip rules we're about to replace (same location).
                if (targetLocationId != null && oldLocationId == targetLocationId) continue
                if (targetLocationId == null) {
                    // Legacy path: PUT /rules without locationId replaces everything, so
                    // there is nothing to collide with after deletion.
                    continue
                }
                val overlaps = newStart < oldRule.endTime && oldRule.startTime < newEnd
                if (overlaps) {
                    throw IllegalArgumentException(
                        "This time overlaps with your schedule at another location " +
                                "(weekday ${newRule.weekday}, existing ${oldRule.startTime}-${oldRule.endTime}). " +
                                "A doctor cannot be at two locations at the same time."
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

    @GetMapping("/validity-periods")
    fun getValidityPeriods(
        @AuthenticationPrincipal principal: DoctorPrincipal
    ): List<ValidityPeriodDto> {
        val d: DoctorProfile = principal.profile
        return scheduleValidityService.getPeriods(d).map {
            ValidityPeriodDto(validFrom = it.validFrom.toString(), validUntil = it.validUntil.toString())
        }
    }

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

        // Multi-location friendly: if the new range is already fully covered by an existing
        // period (equal or contained), treat the call as a no-op. This lets a doctor save a
        // schedule for a second location without having to re-define the validity window.
        val containingPeriod = existingPeriods.firstOrNull { p ->
            !newValidFrom.isBefore(p.validFrom) && !newValidUntil.isAfter(p.validUntil)
        }
        if (containingPeriod != null) {
            logger.info(
                "Calendar period {} – {} is already covered by existing period {} – {}; no-op.",
                newValidFrom, newValidUntil, containingPeriod.validFrom, containingPeriod.validUntil
            )
            return ValidRangeDto(
                validFrom = containingPeriod.validFrom.toString(),
                validUntil = containingPeriod.validUntil.toString()
            )
        }

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
        val slotMinutes: Int,
        val locationId: Long? = null
    )

    @GetMapping("/date-specific")
    fun getDateSpecificRules(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestParam(required = false) locationId: Long?
    ): List<DateSpecificRuleDto> {
        val d: DoctorProfile = principal.profile
        val all = dateSpecificRulesRepo.findByDoctorId(d.id)
        val filtered = if (locationId == null) all else all.filter { it.location?.id == locationId }
        return filtered.map {
            DateSpecificRuleDto(
                id = it.id,
                startDate = it.startDate.toString(),
                endDate = it.endDate.toString(),
                startTime = it.startTime.toString(),
                endTime = it.endTime.toString(),
                slotMinutes = it.slotMinutes,
                locationId = it.location?.id
            )
        }
    }

    @PostMapping("/date-specific")
    @Transactional
    fun createDateSpecificRule(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody body: DateSpecificRuleDto
    ): DateSpecificRuleDto {
        val d: DoctorProfile = principal.profile

        val startDate = LocalDate.parse(body.startDate)
        val endDate = LocalDate.parse(body.endDate)
        val startTime = LocalTime.parse(body.startTime)
        val endTime = LocalTime.parse(body.endTime)

        val zone = try { ZoneId.of(d.timeZone) } catch (_: Exception) { ZoneId.systemDefault() }
        val todayInDoctorZone = LocalDate.now(zone)
        if (startDate.isBefore(todayInDoctorZone)) {
            throw IllegalArgumentException(
                "Cannot add schedule for past dates. Start date $startDate is before today ($todayInDoctorZone in your timezone)."
            )
        }

        validateDateSpecificRule(startDate, endDate, startTime, endTime, body.slotMinutes)

        val targetLocation = resolveDateSpecificLocation(d, body.locationId)
        validateNoOverrides(d.id, startDate, endDate, startTime, endTime, targetLocationId = targetLocation.id)

        val saved = dateSpecificRulesRepo.save(
            DateSpecificScheduleRule(
                doctor = d,
                location = targetLocation,
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
            slotMinutes = saved.slotMinutes,
            locationId = saved.location?.id
        )
    }

    @PutMapping("/date-specific/{id}")
    @Transactional
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

        val zone = try { ZoneId.of(d.timeZone) } catch (_: Exception) { ZoneId.systemDefault() }
        val todayInDoctorZone = LocalDate.now(zone)
        if (startDate.isBefore(todayInDoctorZone)) {
            throw IllegalArgumentException(
                "Cannot add schedule for past dates. Start date $startDate is before today ($todayInDoctorZone in your timezone)."
            )
        }

        validateDateSpecificRule(startDate, endDate, startTime, endTime, body.slotMinutes)

        val targetLocation = resolveDateSpecificLocation(d, body.locationId ?: existing.location?.id)
        validateNoOverrides(
            d.id, startDate, endDate, startTime, endTime,
            targetLocationId = targetLocation.id,
            excludeRuleId = id
        )

        existing.location = targetLocation
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
            slotMinutes = saved.slotMinutes,
            locationId = saved.location?.id
        )
    }

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

    private fun resolveDateSpecificLocation(d: DoctorProfile, locationId: Long?): DoctorLocation {
        if (locationId != null) {
            val loc = locationRepo.findById(locationId)
                .orElseThrow { IllegalArgumentException("Location not found: $locationId") }
            if (loc.doctor.id != d.id) {
                throw IllegalArgumentException("Not your location")
            }
            return loc
        }
        return locationRepo.findByDoctorIdAndIsPrimaryTrue(d.id).orElseGet {
            ensurePrimaryLocationExists(d)
        }
    }

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
     * Rules:
     * 1. Within the SAME location: can only expand (start exactly when existing ends).
     * 2. Across DIFFERENT locations: overlap is forbidden (doctor can't be at two places).
     */
    private fun validateNoOverrides(
        doctorId: Long,
        newStartDate: LocalDate,
        newEndDate: LocalDate,
        newStartTime: LocalTime,
        newEndTime: LocalTime,
        targetLocationId: Long,
        excludeRuleId: Long? = null
    ) {
        val overlappingDateSpecific = dateSpecificRulesRepo
            .findOverlappingWithDateRange(doctorId, newStartDate, newEndDate)
            .filter { excludeRuleId == null || it.id != excludeRuleId }

        var currentDate = newStartDate
        while (!currentDate.isAfter(newEndDate)) {
            val dateSpecificForDay = overlappingDateSpecific.filter { it.appliesTo(currentDate) }

            for (existingRule in dateSpecificForDay) {
                val timeOverlap = newStartTime < existingRule.endTime && existingRule.startTime < newEndTime
                if (!timeOverlap) continue
                val sameLocation = existingRule.location?.id == targetLocationId
                if (sameLocation) {
                    // Allow strict expansion (new starts exactly when existing ends)
                    if (newStartTime != existingRule.endTime) {
                        throw IllegalArgumentException(
                            "Cannot override existing schedule at this location for $currentDate. " +
                                    "Existing: ${existingRule.startTime}-${existingRule.endTime}. " +
                                    "You can only expand after ${existingRule.endTime}."
                        )
                    }
                } else {
                    throw IllegalArgumentException(
                        "This time overlaps with your schedule at another location on $currentDate " +
                                "(${existingRule.startTime}-${existingRule.endTime}). " +
                                "A doctor cannot be at two locations at the same time."
                    )
                }
            }

            val weekday = currentDate.dayOfWeek.value
            val weeklyRules = rulesRepo.findByDoctorId(doctorId).filter { it.weekday == weekday }

            for (weeklyRule in weeklyRules) {
                val timeOverlap = newStartTime < weeklyRule.endTime && weeklyRule.startTime < newEndTime
                if (!timeOverlap) continue
                val sameLocation = weeklyRule.location?.id == targetLocationId
                if (sameLocation) {
                    if (newStartTime != weeklyRule.endTime) {
                        throw IllegalArgumentException(
                            "Cannot override existing weekly schedule at this location for $currentDate (${getWeekdayName(weekday)}). " +
                                    "Existing: ${weeklyRule.startTime}-${weeklyRule.endTime}. " +
                                    "You can only expand after ${weeklyRule.endTime}."
                        )
                    }
                } else {
                    throw IllegalArgumentException(
                        "This time overlaps with your weekly schedule at another location on $currentDate (${getWeekdayName(weekday)}) " +
                                "(${weeklyRule.startTime}-${weeklyRule.endTime}). " +
                                "A doctor cannot be at two locations at the same time."
                    )
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
