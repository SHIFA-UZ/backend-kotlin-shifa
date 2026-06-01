package com.shifa.service

import com.shifa.domain.DoctorProfile
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.DateSpecificScheduleRuleRepository
import com.shifa.repo.ScheduleBlockRepository
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
    private val blocksRepo: ScheduleBlockRepository,
    private val scheduleValidityService: ScheduleValidityService
) {

    data class AvailableSlotDto(
        val startAt: String,
        val endAt: String,
        val slotMinutes: Int,
        /** Practice location this slot belongs to (null = video / legacy). */
        val locationId: Long? = null,
        val locationLabel: String? = null
    )

    /**
     * Returns bookable slots for [doctor] on [localDate]. When [locationId] is non-null, only slots
     * tied to that specific practice location are returned. When null, slots from all locations are
     * merged (classic behavior for doctors with a single location).
     *
     * Appointments across all locations are considered when detecting conflicts, since a doctor
     * cannot be at two physical places at the same time.
     */
    fun availableSlotsForDay(
        doctor: DoctorProfile,
        localDate: LocalDate,
        locationId: Long? = null,
        /** When resizing / moving an appointment, exclude it so its own time stays "free" for validation. */
        excludeAppointmentId: Long? = null
    ): List<AvailableSlotDto> {
        val weekday = localDate.dayOfWeek.value
        val zone = ZoneId.of(doctor.timeZone)

        if (!scheduleValidityService.isDateWithinAnyPeriod(doctor, localDate)) {
            return emptyList()
        }

        val dayStart = localDate.atStartOfDay(zone).toInstant()
        val dayEnd = localDate.plusDays(1).atStartOfDay(zone).toInstant()

        data class RuleForDay(
            val startTime: LocalTime,
            val endTime: LocalTime,
            val slotMinutes: Int,
            val locationId: Long?,
            val locationLabel: String?
        )
        val weeklyRules = rulesRepo.findByDoctorId(doctor.id)
            .filter { it.weekday == weekday }
            .filter { locationId == null || it.location?.id == locationId }
            .map { RuleForDay(it.startTime, it.endTime, it.slotMinutes, it.location?.id, it.location?.label) }
        val dateSpecificRules = dateSpecificRulesRepo.findByDoctorIdAndDate(doctor.id, localDate)
            .filter { locationId == null || it.location?.id == locationId }
            .map { RuleForDay(it.startTime, it.endTime, it.slotMinutes, it.location?.id, it.location?.label) }
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
                                    slotMinutes = r.slotMinutes,
                                    locationId = r.locationId,
                                    locationLabel = r.locationLabel
                                )
                            )
                        )
                    }
                    t = e
                }
            }
        }

        // Conflicts are computed against ALL of this doctor's appointments for the day,
        // regardless of location – a doctor can only be in one place at a time.
        val existingAppts = appts.findOverlapping(doctor.id!!, dayStart, dayEnd)
            .filter { excludeAppointmentId == null || it.id != excludeAppointmentId }
        val blocks = blocksRepo.findOverlapping(doctor.id!!, dayStart, dayEnd)

        fun overlaps(a: Instant, b: Instant, c: Instant, d: Instant) = a < d && c < b

        return freeSlots.filter { (start, end, _) ->
            existingAppts.none { ap -> overlaps(start, end, ap.startAt, ap.endAt) } &&
                blocks.none { block -> overlaps(start, end, block.startAt, block.endAt) }
        }.map { (_, _, dto) -> dto }.sortedBy { it.startAt }
    }

    /**
     * Finds the earliest bookable slot start time for [doctor] at or after [fromInstant], scanning up to
     * [lookaheadDays] days forward. Returns null when no slot is available in that window. Used by copilot
     * doctor ranking to prefer providers with soonest availability.
     */
    fun nextAvailableStartAt(
        doctor: DoctorProfile,
        fromInstant: Instant,
        lookaheadDays: Int = 14
    ): Instant? {
        val zone = ZoneId.of(doctor.timeZone)
        val startDate = fromInstant.atZone(zone).toLocalDate()
        for (i in 0..lookaheadDays) {
            val date = startDate.plusDays(i.toLong())
            val slots = availableSlotsForDay(doctor, date)
            for (s in slots) {
                val slotStart = Instant.parse(s.startAt)
                if (!slotStart.isBefore(fromInstant)) return slotStart
            }
        }
        return null
    }
}
