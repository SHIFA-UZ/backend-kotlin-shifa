package com.shifa.service

import com.shifa.domain.DoctorProfile
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.DateSpecificScheduleRuleRepository
import com.shifa.repo.WeeklyScheduleRuleRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Computes available appointment slots for a patient-facing doctor day view.
 * Shared by [com.shifa.web.PatientScheduleController] and copilot auto-booking.
 */
@Service
class PatientDaySlotsService(
    private val rulesRepo: WeeklyScheduleRuleRepository,
    private val dateSpecificRulesRepo: DateSpecificScheduleRuleRepository,
    private val appts: AppointmentRepository,
    private val scheduleValidityService: ScheduleValidityService
) {

    data class AvailableSlotDto(
        val startAt: String,
        val endAt: String,
        val slotMinutes: Int
    )

    fun availableSlotsForDay(doctor: DoctorProfile, localDate: LocalDate): List<AvailableSlotDto> {
        val weekday = localDate.dayOfWeek.value
        val zone = ZoneId.of(doctor.timeZone)

        if (!scheduleValidityService.isDateWithinAnyPeriod(doctor, localDate)) {
            return emptyList()
        }

        val dayStart = localDate.atStartOfDay(zone).toInstant()
        val dayEnd = localDate.plusDays(1).atStartOfDay(zone).toInstant()

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

        val existingAppts = appts.findOverlapping(doctor.id!!, dayStart, dayEnd)

        fun overlaps(a: Instant, b: Instant, c: Instant, d: Instant) = a < d && c < b

        return freeSlots.filter { (start, end, _) ->
            existingAppts.none { ap -> overlaps(start, end, ap.startAt, ap.endAt) }
        }.map { (_, _, dto) -> dto }.sortedBy { it.startAt }
    }
}
