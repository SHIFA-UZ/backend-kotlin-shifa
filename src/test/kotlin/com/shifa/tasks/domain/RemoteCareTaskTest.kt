package com.shifa.tasks.domain

import com.shifa.doctors.domain.DoctorProfile
import com.shifa.patients.domain.PatientProfile
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class RemoteCareTaskTest {
    private val start = LocalDate.of(2026, 11, 1)

    private fun task(endDate: LocalDate? = null, durationDays: Int? = null) =
        RemoteCareTask(
            doctor = mock(DoctorProfile::class.java),
            patient = mock(PatientProfile::class.java),
            taskName = "Blood pressure",
            category = RemoteCareTask.Category.VITAL,
            timesPerDay = 3,
            startDate = start,
            endDate = endDate,
            durationDays = durationDays,
            inputType = RemoteCareTask.InputType.NUMERIC,
        )

    @Test
    fun `a duration counts the start day`() {
        assertEquals(start, task(durationDays = 1).lastScheduledDate())
        assertEquals(start.plusDays(6), task(durationDays = 7).lastScheduledDate())
    }

    @Test
    fun `an end date wins, and with neither the task runs 30 days`() {
        assertEquals(start.plusDays(2), task(endDate = start.plusDays(2), durationDays = 1).lastScheduledDate())
        assertEquals(start.plusDays(29), task().lastScheduledDate())
    }

    @Test
    fun `a duration below one is still the start day`() {
        assertEquals(start, task(durationDays = 0).lastScheduledDate())
    }
}
