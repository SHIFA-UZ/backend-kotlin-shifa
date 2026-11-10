package com.shifa.appointments.application

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
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class PatientDaySlotsNextAvailableTest {

    private val rules = mock(WeeklyScheduleRuleRepository::class.java)
    private val dateRules = mock(DateSpecificScheduleRuleRepository::class.java)
    private val appts = mock(AppointmentRepository::class.java)
    private val blocks = mock(ScheduleBlockRepository::class.java)
    private val validity = mock(ScheduleValidityService::class.java)
    private val service = PatientDaySlotsService(rules, dateRules, appts, blocks, validity)

    private fun anyInstant(): Instant = ArgumentMatchers.any(Instant::class.java) ?: Instant.EPOCH

    private fun doctor(id: Long, zone: String) =
        DoctorProfile(id = id, user = User(id = id + 100, passwordHash = "x"), firstName = "D", lastName = "$id")
            .also { it.timeZone = zone }

    /** Monday 2030-01-07, 09:00 in Tashkent (UTC+5). */
    private val monday0900 = Instant.parse("2030-01-07T04:00:00Z")
    private val tashkent = doctor(9, "Asia/Tashkent")

    @Test
    fun `a cancelled appointment does not hide its slot`() {
        `when`(rules.findByDoctorId(9)).thenReturn(
            listOf(WeeklyScheduleRule(doctor = tashkent, weekday = 1, startTime = LocalTime.of(9, 0), endTime = LocalTime.of(10, 0), slotMinutes = 30)),
        )
        `when`(validity.getPeriods(tashkent)).thenReturn(
            listOf(ScheduleValidityPeriod(doctor = tashkent, validFrom = LocalDate.of(2030, 1, 1), validUntil = LocalDate.of(2030, 12, 31))),
        )
        // The appointments that hold a slot: none (the only one at 09:00 is cancelled, so the query leaves it out).
        `when`(appts.findOverlapping(anyLong(), anyInstant(), anyInstant())).thenReturn(emptyList())

        assertEquals(monday0900, service.nextAvailableStartAt(tashkent, monday0900.minusSeconds(3600), 14))
        // The unfiltered range query counted cancelled visits as busy.
        verify(appts, never()).findByDoctorIdAndStartAtBetween(anyLong(), anyInstant(), anyInstant())
    }
}
