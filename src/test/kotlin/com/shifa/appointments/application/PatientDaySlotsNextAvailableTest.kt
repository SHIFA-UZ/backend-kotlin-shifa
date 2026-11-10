package com.shifa.appointments.application

import com.shifa.appointments.domain.DateSpecificScheduleRule
import com.shifa.appointments.domain.ScheduleBlock
import com.shifa.appointments.domain.ScheduleValidityPeriod
import com.shifa.appointments.domain.WeeklyScheduleRule
import com.shifa.appointments.infrastructure.AppointmentRepository
import com.shifa.appointments.infrastructure.DateSpecificScheduleRuleRepository
import com.shifa.appointments.infrastructure.ScheduleBlockRepository
import com.shifa.appointments.infrastructure.WeeklyScheduleRuleRepository
import com.shifa.auth.domain.User
import com.shifa.doctors.domain.DoctorProfile
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * The next available time, for one doctor and for a batch. The repositories answer from one set of rows filtered by
 * the doctor ids asked for, so a batch that mixed the doctors' rows up would not match the one-doctor answers.
 */
class PatientDaySlotsNextAvailableTest {

    private fun doctor(id: Long, zone: String) =
        DoctorProfile(id = id, user = User(id = id + 100, passwordHash = "x"), firstName = "D", lastName = "$id")
            .also { it.timeZone = zone }

    private val tashkent = doctor(9, "Asia/Tashkent") // UTC+5
    private val berlin = doctor(10, "Europe/Berlin") // UTC+1 in January
    private val noPeriod = doctor(11, "Asia/Tashkent")
    private val blocked = doctor(12, "Asia/Tashkent")
    private val all = listOf(tashkent, berlin, noPeriod, blocked)

    private fun weekly(doctor: DoctorProfile, weekday: Int, from: Int, to: Int) =
        WeeklyScheduleRule(doctor = doctor, weekday = weekday, startTime = LocalTime.of(from, 0), endTime = LocalTime.of(to, 0), slotMinutes = 30)

    private val year = LocalDate.of(2030, 1, 1) to LocalDate.of(2030, 12, 31)
    private val weeklyRows = listOf(weekly(tashkent, 1, 9, 10), weekly(noPeriod, 1, 9, 10), weekly(blocked, 1, 9, 10))
    private val dateRows = listOf(
        // Berlin works only on Wednesday 2030-01-09, 14:00-15:00.
        DateSpecificScheduleRule(
            doctor = berlin, startDate = LocalDate.of(2030, 1, 9), endDate = LocalDate.of(2030, 1, 9),
            startTime = LocalTime.of(14, 0), endTime = LocalTime.of(15, 0), slotMinutes = 30,
        ),
    )
    private val periodRows = listOf(tashkent, berlin, blocked)
        .associate { it.id to listOf(ScheduleValidityPeriod(doctor = it, validFrom = year.first, validUntil = year.second)) }

    /** Monday 2030-01-07, 09:00 in Tashkent. */
    private val monday0900 = Instant.parse("2030-01-07T04:00:00Z")
    private val from = monday0900.minusSeconds(3600)

    /** (doctor id, start, end) of the visits that hold a slot: Tashkent's 09:00, Berlin's 14:00 on the 9th. */
    private val busyRows = listOf(
        arrayOf<Any>(9L, monday0900, monday0900.plusSeconds(1800)),
        arrayOf<Any>(10L, Instant.parse("2030-01-09T13:00:00Z"), Instant.parse("2030-01-09T13:30:00Z")),
    )
    /** The fourth doctor is blocked for the whole first Monday. */
    private val blockRows = listOf(
        ScheduleBlock(doctor = blocked, startAt = Instant.parse("2030-01-06T19:00:00Z"), endAt = Instant.parse("2030-01-07T19:00:00Z")),
    )

    private fun anyInstant(): Instant = ArgumentMatchers.any(Instant::class.java) ?: Instant.EPOCH
    private fun anyDate(): LocalDate = ArgumentMatchers.any(LocalDate::class.java) ?: LocalDate.MIN
    private fun anyIds(): Collection<Long> = ArgumentMatchers.anyCollection<Long>() ?: emptyList()
    private fun anyDoctors(): Collection<DoctorProfile> = ArgumentMatchers.anyCollection<DoctorProfile>() ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun ids(call: org.mockito.invocation.InvocationOnMock) = call.getArgument<Collection<Long>>(0)

    private val rules = mock(WeeklyScheduleRuleRepository::class.java).also { repo ->
        `when`(repo.findByDoctorIdIn(anyIds())).thenAnswer { call -> weeklyRows.filter { it.doctor.id in ids(call) } }
    }
    private val dateRules = mock(DateSpecificScheduleRuleRepository::class.java).also { repo ->
        `when`(repo.findOverlappingWithDateRangeForDoctors(anyIds(), anyDate(), anyDate()))
            .thenAnswer { call -> dateRows.filter { it.doctor.id in ids(call) } }
    }
    private val appts = mock(AppointmentRepository::class.java).also { repo ->
        `when`(repo.findBusyTimesForDoctors(anyIds(), anyInstant(), anyInstant()))
            .thenAnswer { call -> busyRows.filter { it[0] in ids(call) } }
    }
    private val blocks = mock(ScheduleBlockRepository::class.java).also { repo ->
        `when`(repo.findOverlappingForDoctors(anyIds(), anyInstant(), anyInstant()))
            .thenAnswer { call -> blockRows.filter { it.doctor.id in ids(call) } }
    }
    private val validity = mock(ScheduleValidityService::class.java).also { service ->
        `when`(service.getPeriods(anyDoctors())).thenAnswer { call ->
            call.getArgument<Collection<DoctorProfile>>(0).associate { it.id to periodRows[it.id].orEmpty() }
        }
    }
    private val service = PatientDaySlotsService(rules, dateRules, appts, blocks, validity)

    @Test
    fun `each doctor gets the first free slot of their own schedule`() {
        // Tashkent: 09:00 is taken, so 09:30. Only visits that hold a slot are read (a cancelled one frees its time).
        assertEquals(monday0900.plusSeconds(1800), service.nextAvailableStartAt(tashkent, from, 14))
        verify(appts, never()).findByDoctorIdAndStartAtBetween(anyLong(), anyInstant(), anyInstant())
        // Berlin: 14:00 on the 9th is taken, so 14:30 Berlin time.
        assertEquals(Instant.parse("2030-01-09T13:30:00Z"), service.nextAvailableStartAt(berlin, from, 14))
        // No validity period: nothing is bookable.
        assertNull(service.nextAvailableStartAt(noPeriod, from, 14))
        // Blocked on the first Monday: the Monday after.
        assertEquals(Instant.parse("2030-01-14T04:00:00Z"), service.nextAvailableStartAt(blocked, from, 14))
        // Outside the window there is nothing.
        assertNull(service.nextAvailableStartAt(berlin, from, 1))
    }

    @Test
    fun `a batch gives every doctor the answer they get alone`() {
        val alone = all.mapNotNull { doctor -> service.nextAvailableStartAt(doctor, from, 14)?.let { doctor.id to it } }.toMap()

        assertEquals(alone, service.nextAvailableStartAt(all, from, 14))
        assertEquals(setOf(9L, 10L, 12L), alone.keys)
        assertEquals(emptyMap<Long, Instant>(), service.nextAvailableStartAt(emptyList(), from, 14))
    }
}
