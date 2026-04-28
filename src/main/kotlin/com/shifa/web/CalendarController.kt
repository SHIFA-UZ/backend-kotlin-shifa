// src/main/kotlin/com/shifa/web/CalendarController.kt
package com.shifa.web

import com.shifa.repo.AppointmentRepository
import com.shifa.repo.DateSpecificScheduleRuleRepository
import com.shifa.repo.WeeklyScheduleRuleRepository
import com.shifa.security.DoctorPrincipal
import com.shifa.service.PatientProfileMapper
import com.shifa.service.ScheduleValidityService
import org.slf4j.LoggerFactory
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.*

@RestController
@RequestMapping("/api/calendar")
class CalendarController(
    private val rulesRepo: WeeklyScheduleRuleRepository,
    private val dateSpecificRulesRepo: DateSpecificScheduleRuleRepository,
    private val appts: AppointmentRepository,
    private val profileMapper: PatientProfileMapper,
    private val scheduleValidityService: ScheduleValidityService
) {
    private val logger = LoggerFactory.getLogger(CalendarController::class.java)

    data class EntryDto(
        val type: String, // FREE_SLOT | APPOINTMENT
        val startAt: String, // ISO 8601 UTC
        val endAt: String,
        val appointmentId: Long? = null,
        val patientId: Long? = null,
        val patientName: String? = null,
        val patientPhotoUrl: String? = null,
        val location: String? = null,
        val reason: String? = null,
        val status: String? = null, // Appointment status: CONFIRMED, COMPLETED, CANCELLED, REQUESTED
        val paymentStatus: String? = null, // For video payment visibility in doctor calendar
        val signatureRequested: Boolean = false,
        val patientSignedAt: String? = null, // ISO when patient signed
        /** Which practice location this slot/appointment belongs to (null for video/legacy). */
        val locationId: Long? = null,
        val locationLabel: String? = null
    )

    @GetMapping
    fun byDay(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestParam day: String,
        @RequestParam(required = false) locationId: Long?
    ): List<EntryDto> {

        val doctor = principal.profile
        val doctorId = doctor.id
        
        // Defensive check: Ensure doctor ID is valid (should never be <= 0 for a real doctor)
        if (doctorId <= 0) {
            logger.error("Invalid doctor ID: $doctorId for user ${doctor.user.id}. Returning empty calendar.")
            return emptyList()
        }
        
        logger.debug("Calendar request: doctorId=$doctorId, day=$day")
        
        val localDate = LocalDate.parse(day)
        val weekday = localDate.dayOfWeek.value
        val zone = ZoneId.of(doctor.timeZone)

        val dayStart = localDate.atStartOfDay(zone).toInstant()
        val dayEnd = localDate.plusDays(1).atStartOfDay(zone).toInstant()

        // ---------- FREE SLOTS ----------
        // Calendar validity: date must fall within at least one validity period (multiple periods supported).
        val dateOutsideValidity = !scheduleValidityService.isDateWithinAnyPeriod(doctor, localDate)

        data class FreeSlot(
            val start: Instant,
            val end: Instant,
            val dto: EntryDto
        )

        // Maximum slots per day to prevent memory explosion
        // If slotMinutes is 1 minute and schedule is 24 hours, that's 1440 slots!
        // We limit to 200 slots per day (reasonable for a doctor's schedule)
        val MAX_SLOTS_PER_DAY = 200
        
        // Use a Set to track slot start times and prevent duplicates from overlapping rules
        val slotStarts = mutableSetOf<Instant>()
        
        // Helper data class to unify weekly and date-specific rules (carries location too).
        data class ScheduleRuleForDay(
            val startTime: LocalTime,
            val endTime: LocalTime,
            val slotMinutes: Int,
            val locationId: Long?,
            val locationLabel: String?
        )
        
        val freeSlots = buildList {
            if (dateOutsideValidity) {
                // Strict upper/lower bound: no free slots outside calendar validity
                return@buildList
            }

            // Extension is additive: combine weekly rules (for this weekday) and
            // date-specific rules (for this date). Optionally filter to one location.
            val weeklyForDay = rulesRepo.findByDoctorId(doctor.id)
                .filter { it.weekday == weekday }
                .filter { locationId == null || it.location?.id == locationId }
                .map { rule ->
                    ScheduleRuleForDay(
                        startTime = rule.startTime,
                        endTime = rule.endTime,
                        slotMinutes = rule.slotMinutes,
                        locationId = rule.location?.id,
                        locationLabel = rule.location?.label
                    )
                }
            val dateSpecificRules = dateSpecificRulesRepo.findByDoctorIdAndDate(doctor.id, localDate)
            val dateSpecificForDay = dateSpecificRules
                .filter { locationId == null || it.location?.id == locationId }
                .map { rule ->
                    ScheduleRuleForDay(
                        startTime = rule.startTime,
                        endTime = rule.endTime,
                        slotMinutes = rule.slotMinutes,
                        locationId = rule.location?.id,
                        locationLabel = rule.location?.label
                    )
                }
            val rulesForDay = (weeklyForDay + dateSpecificForDay)
            
            // Sort rules by start time to process them in order
            val sortedRules = rulesForDay.sortedBy { it.startTime }
            
            var totalSlots = 0
            for (r in sortedRules) {
                // Validate slotMinutes to prevent extremely small slots (minimum 5 minutes)
                val step = maxOf(r.slotMinutes.toLong(), 5)
                
                // Calculate how many slots this rule would generate
                val durationMinutes = java.time.Duration.between(r.startTime, r.endTime).toMinutes()
                val slotsForThisRule = (durationMinutes / step).toInt()
                
                // Skip if this would exceed the limit
                if (totalSlots + slotsForThisRule > MAX_SLOTS_PER_DAY) {
                    // Generate only remaining slots up to limit
                    val remainingSlots = MAX_SLOTS_PER_DAY - totalSlots
                    if (remainingSlots <= 0) break
                    
                    var t = r.startTime
                    var generated = 0
                    while (t.plusMinutes(step) <= r.endTime && generated < remainingSlots) {
                        val e = t.plusMinutes(step)
                        val startLdt = LocalDateTime.of(localDate, t)
                        val endLdt = LocalDateTime.of(localDate, e)
                        val startInstant = startLdt.atZone(zone).toInstant()
                        
                        // Skip if this slot overlaps with a previously generated slot
                        if (slotStarts.add(startInstant)) {
                            add(
                                FreeSlot(
                                    start = startInstant,
                                    end = endLdt.atZone(zone).toInstant(),
                                    dto = EntryDto(
                                        type = "FREE_SLOT",
                                        startAt = startInstant.toString(),
                                        endAt = endLdt.atZone(zone).toInstant().toString(),
                                        locationId = r.locationId,
                                        locationLabel = r.locationLabel
                                    )
                                )
                            )
                            generated++
                            totalSlots++
                        }
                        t = e
                    }
                    break
                }
                
                // Generate all slots for this rule
                var t = r.startTime
                while (t.plusMinutes(step) <= r.endTime) {
                    val e = t.plusMinutes(step)
                    val startLdt = LocalDateTime.of(localDate, t)
                    val endLdt = LocalDateTime.of(localDate, e)
                    val startInstant = startLdt.atZone(zone).toInstant()
                    
                    // Skip if this slot overlaps with a previously generated slot (from another rule)
                    if (slotStarts.add(startInstant)) {
                        add(
                            FreeSlot(
                                start = startInstant,
                                end = endLdt.atZone(zone).toInstant(),
                                dto = EntryDto(
                                    type = "FREE_SLOT",
                                    startAt = startInstant.toString(),
                                    endAt = endLdt.atZone(zone).toInstant().toString(),
                                    locationId = r.locationId,
                                    locationLabel = r.locationLabel
                                )
                            )
                        )
                        totalSlots++
                    }
                    t = e
                }
            }
        }

        // ---------- APPOINTMENTS ----------
        // CRITICAL: Only return appointments that belong to this specific doctor
        // Add defensive filtering to ensure no cross-doctor data leakage
        val apptsForDay = appts
            .findOverlapping(doctorId, dayStart, dayEnd)
            .filter { appointment ->
                // Defensive check: Ensure appointment belongs to this doctor
                val belongsToDoctor = appointment.doctor.id == doctorId
                if (!belongsToDoctor) {
                    logger.error(
                        "SECURITY: Appointment ${appointment.id} belongs to doctor ${appointment.doctor.id} " +
                        "but was returned for doctor $doctorId. Filtering out."
                    )
                }
                // Additional check: Ensure status is valid (not CANCELLED)
                val isValidStatus = appointment.status != com.shifa.domain.Appointment.Status.CANCELLED
                if (!isValidStatus) {
                    logger.warn("Appointment ${appointment.id} has status ${appointment.status}, filtering out")
                }
                val matchesLocation = when {
                    locationId == null -> true
                    // Include video appointments (no locationRef) when filtering by a physical location? No: keep strict.
                    else -> appointment.locationRef?.id == locationId
                }
                belongsToDoctor && isValidStatus && matchesLocation
            }
            .map { ap ->
                FreeSlot(
                    start = ap.startAt,
                    end = ap.endAt,
                    dto = EntryDto(
                        type = "APPOINTMENT",
                        startAt = ap.startAt.toString(),
                        endAt = ap.endAt.toString(),
                        appointmentId = ap.id,
                        patientId = ap.patient?.id,
                        patientName = ap.patient?.fullName,
                        patientPhotoUrl = profileMapper.normalizePhotoUrl(ap.patient?.photoUrl),
                        location = ap.location,
                        reason = ap.reason,
                        status = ap.status.name,
                        paymentStatus = ap.paymentStatus.name,
                        signatureRequested = ap.signatureRequested,
                        patientSignedAt = ap.patientSignedAt?.toString(),
                        locationId = ap.locationRef?.id,
                        locationLabel = ap.locationRef?.label
                    )
                )
            }
        
        logger.debug("Found ${apptsForDay.size} appointments for doctor $doctorId on $day")

        fun overlaps(a: Instant, b: Instant, c: Instant, d: Instant) =
            a < d && c < b

        // Optimize filtering: sort appointments by start time for faster lookup
        val sortedAppts = apptsForDay.sortedBy { it.start }
        
        // Use binary search-like approach for better performance
        val filteredFreeSlots = freeSlots.filter { fs ->
            sortedAppts.none { ap -> overlaps(fs.start, fs.end, ap.start, ap.end) }
        }

        val result = (apptsForDay.map { it.dto } + filteredFreeSlots.map { it.dto })
            .sortedBy { Instant.parse(it.startAt) }
        
        logger.debug("Returning ${result.size} entries (${apptsForDay.size} appointments, ${filteredFreeSlots.size} free slots) for doctor $doctorId on $day")
        
        return result
    }
}
