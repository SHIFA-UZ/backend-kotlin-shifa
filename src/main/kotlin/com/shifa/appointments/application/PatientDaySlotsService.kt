package com.shifa.appointments.application

import com.shifa.doctors.domain.DoctorProfile
import com.shifa.appointments.infrastructure.AppointmentRepository
import com.shifa.appointments.infrastructure.DateSpecificScheduleRuleRepository
import com.shifa.appointments.infrastructure.ScheduleBlockRepository
import com.shifa.appointments.infrastructure.WeeklyScheduleRuleRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.springframework.transaction.annotation.Transactional

/**
 * Computes available appointment slots for a patient-facing doctor day view.
 * Shared by [com.shifa.appointments.api.PatientScheduleController] and copilot auto-booking.
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
    @Transactional
    fun availableSlotsForDay(
        doctor: DoctorProfile,
        localDate: LocalDate,
        locationId: Long? = null,
        /** When resizing / moving an appointment, exclude it so its own time stays "free" for validation. */
        excludeAppointmentId: Long? = null
    ): List<AvailableSlotDto> {
        val weekday = localDate.dayOfWeek.value
        val zone = ZoneId.of(doctor.timeZone)

        // The validity periods bound the weekly template only; extra hours for this date count either way.
        val weeklyValid = scheduleValidityService.isDateWithinAnyPeriod(doctor, localDate)

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
            .filter { weeklyValid && it.weekday == weekday }
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

                while (com.shifa.appointments.domain.slotEnd(t, step, r.endTime) != null) {
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
    @Transactional
    fun nextAvailableStartAt(
        doctor: DoctorProfile,
        fromInstant: Instant,
        lookaheadDays: Int = 14
    ): Instant? = nextAvailableStartAt(listOf(doctor), fromInstant, lookaheadDays)[doctor.id]

    /**
     * [nextAvailableStartAt] for several doctors, by doctor id (no entry: no slot in the window). Schedule rules,
     * validity periods, appointments and blocks are read once for all of them: five queries for a page of the public
     * doctor list instead of five per doctor.
     */
    @Transactional
    fun nextAvailableStartAt(
        doctors: Collection<DoctorProfile>,
        fromInstant: Instant,
        lookaheadDays: Int = 14,
    ): Map<Long, Instant> {
        if (doctors.isEmpty()) return emptyMap()
        val ids = doctors.map { it.id }
        // Each doctor's window is in their own time zone; read the widest one, the per-day filters narrow it again.
        val startDates = doctors.map { fromInstant.atZone(ZoneId.of(it.timeZone)).toLocalDate() }
        val firstDate = startDates.min()
        val lastDate = startDates.max().plusDays(lookaheadDays.toLong())
        val rangeStart = firstDate.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val rangeEnd = lastDate.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant()

        val weeklyRules = rulesRepo.findByDoctorIdIn(ids).groupBy { it.doctor.id }
        val dateSpecificRules =
            dateSpecificRulesRepo.findOverlappingWithDateRangeForDoctors(ids, firstDate, lastDate).groupBy { it.doctor.id }
        val validityPeriods = scheduleValidityService.getPeriods(doctors)
        val busy = (
            appts.findBusyTimesForDoctors(ids, rangeStart, rangeEnd).map { (it[0] as Long) to Busy(it[1] as Instant, it[2] as Instant) } +
                blocksRepo.findOverlappingForDoctors(ids, rangeStart, rangeEnd).map { it.doctor.id to Busy(it.startAt, it.endAt) }
            ).groupBy({ it.first }, { it.second })

        val result = HashMap<Long, Instant>()
        for (doctor in doctors) {
            val next = firstSlot(
                doctor = doctor,
                fromInstant = fromInstant,
                lookaheadDays = lookaheadDays,
                weeklyRules = weeklyRules[doctor.id].orEmpty(),
                dateSpecificRules = dateSpecificRules[doctor.id].orEmpty(),
                validityPeriods = validityPeriods[doctor.id].orEmpty(),
                busy = busy[doctor.id].orEmpty(),
            )
            if (next != null) result[doctor.id] = next
        }
        return result
    }

    /** A time the doctor is taken: an appointment that holds its slot, or a block. */
    private data class Busy(val startAt: Instant, val endAt: Instant)

    private fun firstSlot(
        doctor: DoctorProfile,
        fromInstant: Instant,
        lookaheadDays: Int,
        weeklyRules: List<com.shifa.appointments.domain.WeeklyScheduleRule>,
        dateSpecificRules: List<com.shifa.appointments.domain.DateSpecificScheduleRule>,
        validityPeriods: List<com.shifa.appointments.domain.ScheduleValidityPeriod>,
        busy: List<Busy>,
    ): Instant? {
        // No rules: nothing to offer. The validity periods bound the weekly template only (as availableSlotsForDay
        // has it): a date outside them offers its extra hours alone.
        if (weeklyRules.isEmpty() && dateSpecificRules.isEmpty()) return null
        val zone = ZoneId.of(doctor.timeZone)
        val startDate = fromInstant.atZone(zone).toLocalDate()
        for (i in 0..lookaheadDays) {
            val date = startDate.plusDays(i.toLong())
            val weeklyValid = validityPeriods.any { it.contains(date) }
            val slots = availableSlotsForDayWithPrefetch(
                localDate = date,
                zone = zone,
                weeklyRules = if (weeklyValid) weeklyRules else emptyList(),
                dateSpecificRules = dateSpecificRules,
                busy = busy,
            )
            for (s in slots) {
                val slotStart = Instant.parse(s.startAt)
                if (!slotStart.isBefore(fromInstant)) return slotStart
            }
        }
        return null
    }

    private fun availableSlotsForDayWithPrefetch(
        localDate: LocalDate,
        zone: ZoneId,
        weeklyRules: List<com.shifa.appointments.domain.WeeklyScheduleRule>,
        dateSpecificRules: List<com.shifa.appointments.domain.DateSpecificScheduleRule>,
        busy: List<Busy>,
        locationId: Long? = null,
    ): List<AvailableSlotDto> {
        val weekday = localDate.dayOfWeek.value

        val dayStart = localDate.atStartOfDay(zone).toInstant()
        val dayEnd = localDate.plusDays(1).atStartOfDay(zone).toInstant()

        data class RuleForDay(
            val startTime: LocalTime,
            val endTime: LocalTime,
            val slotMinutes: Int,
            val locationId: Long?,
            val locationLabel: String?
        )
        val weeklyForDay = weeklyRules
            .filter { it.weekday == weekday }
            .filter { locationId == null || it.location?.id == locationId }
            .map { RuleForDay(it.startTime, it.endTime, it.slotMinutes, it.location?.id, it.location?.label) }
        val dateSpecificForDay = dateSpecificRules
            .filter { it.appliesTo(localDate) }
            .filter { locationId == null || it.location?.id == locationId }
            .map { RuleForDay(it.startTime, it.endTime, it.slotMinutes, it.location?.id, it.location?.label) }
        val allRules = (weeklyForDay + dateSpecificForDay).sortedBy { it.startTime }

        val slotStarts = mutableSetOf<Instant>()
        val freeSlots = buildList {
            for (r in allRules) {
                var t = r.startTime
                val step = maxOf(r.slotMinutes.toLong(), 5)

                while (com.shifa.appointments.domain.slotEnd(t, step, r.endTime) != null) {
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

        val dayBusy = busy.filter { it.startAt < dayEnd && it.endAt > dayStart }

        fun overlaps(a: Instant, b: Instant, c: Instant, d: Instant) = a < d && c < b

        return freeSlots.filter { (start, end, _) ->
            dayBusy.none { overlaps(start, end, it.startAt, it.endAt) }
        }.map { (_, _, dto) -> dto }.sortedBy { it.startAt }
    }
}
