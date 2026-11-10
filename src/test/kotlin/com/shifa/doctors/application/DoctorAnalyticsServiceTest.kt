package com.shifa.doctors.application

import com.shifa.appointments.application.Appointments
import com.shifa.appointments.domain.Appointment.Status
import com.shifa.doctors.domain.DoctorProfile
import org.junit.jupiter.api.Assertions.assertEquals
import java.time.Instant
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class DoctorAnalyticsServiceTest {

    private fun anyInstant(): Instant = ArgumentMatchers.any(Instant::class.java) ?: Instant.EPOCH

    @Test
    fun `overview adds up the per-status counts the way the separate count queries did`() {
        val appts = mock(Appointments::class.java)
        val doctor = mock(DoctorProfile::class.java)
        `when`(doctor.id).thenReturn(7L)
        `when`(doctor.timeZone).thenReturn("Asia/Tashkent")
        `when`(appts.countByStatusForDoctorBetween(anyLong(), anyInstant(), anyInstant())).thenReturn(
            mapOf(
                Status.REQUESTED to 1L, Status.CONFIRMED to 2L, Status.IN_PROGRESS to 3L, Status.COMPLETED to 4L,
                Status.CANCELLED to 5L, Status.EXPIRED to 6L,
            ),
        )
        `when`(appts.countNewPatientsToday(anyLong(), anyInstant(), anyInstant())).thenReturn(2L)

        val overview = DoctorAnalyticsService(
            appts,
            mock(com.shifa.documents.application.PatientDocumentService::class.java),
            mock(com.shifa.notifications.application.DoctorSmsBillingService::class.java),
        ).overview(doctor)

        assertEquals(10, overview.appointmentsToday) // requested + confirmed + in progress + completed
        assertEquals(4, overview.completedToday)
        assertEquals(5, overview.cancelledToday)
        assertEquals(2, overview.newPatientsToday)
    }
}
