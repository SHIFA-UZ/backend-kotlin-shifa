// src/main/kotlin/com/shifa/web/PatientScheduleController.kt
package com.shifa.web

import java.time.Instant
import java.time.ZoneId
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.DateSpecificScheduleRuleRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.WeeklyScheduleRuleRepository
import com.shifa.security.PatientPrincipal
import com.shifa.service.ScheduleValidityService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.*

@RestController
@RequestMapping("/api/patients/me/schedule")
class PatientScheduleController(
    private val doctorProfiles: DoctorProfileRepository,
    private val rulesRepo: WeeklyScheduleRuleRepository,
    private val dateSpecificRulesRepo: DateSpecificScheduleRuleRepository,
    private val appts: AppointmentRepository,
    private val scheduleValidityService: ScheduleValidityService
) {

    data class AvailableSlotDto(
        val startAt: String, // ISO 8601 UTC
        val endAt: String,
        val slotMinutes: Int
    )

    /**
     * GET /api/patients/me/schedule/doctors/{doctorId}/available
     * Get available time slots for a doctor on a specific day
     */
    @GetMapping("/doctors/{doctorId}/available")
    fun getAvailableSlots(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @PathVariable doctorId: Long,
        @RequestParam day: String // yyyy-MM-dd
    ): List<AvailableSlotDto> {
        val doctor = doctorProfiles.findById(doctorId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found") }

        val localDate = LocalDate.parse(day)
        val weekday = localDate.dayOfWeek.value
        val zone = ZoneId.of(doctor.timeZone)

        // Calendar validity: date must fall within at least one validity period (multiple periods supported)
        if (!scheduleValidityService.isDateWithinAnyPeriod(doctor, localDate)) {
            return emptyList()
        }

        val dayStart = localDate.atStartOfDay(zone).toInstant()
        val dayEnd = localDate.plusDays(1).atStartOfDay(zone).toInstant()

        // Combine weekly rules (for this weekday) and date-specific rules (additive, no replacement)
        data class RuleForDay(val startTime: LocalTime, val endTime: LocalTime, val slotMinutes: Int)
        val weeklyRules = rulesRepo.findByDoctorId(doctor.id)
            .filter { it.weekday == weekday }
            .map { RuleForDay(it.startTime, it.endTime, it.slotMinutes) }
        val dateSpecificRules = dateSpecificRulesRepo.findByDoctorIdAndDate(doctor.id, localDate)
            .map { RuleForDay(it.startTime, it.endTime, it.slotMinutes) }
        val allRules = (weeklyRules + dateSpecificRules).sortedBy { it.startTime }

        val slotStarts = mutableSetOf<Instant>()
        val freeSlots = buildList {
            for (r in allRules) {
                var t = r.startTime
                val step = maxOf(r.slotMinutes.toLong(), 5)

                while (t.plusMinutes(step) <= r.endTime) {
                    val e = t.plusMinutes(step)

                    val startLdt = LocalDateTime.of(localDate, t)
                    val endLdt = LocalDateTime.of(localDate, e)

                    val startInstant = startLdt.atZone(zone).toInstant()
                    val endInstant = endLdt.atZone(zone).toInstant()

                    if (slotStarts.add(startInstant)) {
                        add(
                            Triple(
                                startInstant,
                                endInstant,
                                AvailableSlotDto(
                                    startAt = startInstant.toString(),
                                    endAt = endInstant.toString(),
                                    slotMinutes = r.slotMinutes
                                )
                            )
                        )
                    }
                    t = e
                }
            }
        }

        // Get existing appointments for this day
        val existingAppts = appts.findOverlapping(doctor.id, dayStart, dayEnd)

        // Filter out slots that overlap with existing appointments
        fun overlaps(a: Instant, b: Instant, c: Instant, d: Instant) = a < d && c < b

        val availableSlots = freeSlots.filter { (start, end, _) ->
            existingAppts.none { ap -> overlaps(start, end, ap.startAt, ap.endAt) }
        }.map { (_, _, dto) -> dto }

        return availableSlots.sortedBy { it.startAt }
    }
}
