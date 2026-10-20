package com.shifa.intelligence.appointment

import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.domain.Appointment
import com.shifa.domain.DoctorProfile
import com.shifa.domain.PatientProfile
import com.shifa.domain.User
import com.shifa.service.DoctorAppointmentBookingService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

class IntelligenceBookingWorkflowServiceTest {
    private val now = Instant.parse("2026-10-20T10:00:00Z")
    private val repository = mock(IntelligenceBookingWorkflowRepository::class.java)
    private val booking = mock(DoctorAppointmentBookingService::class.java)
    private val service = IntelligenceBookingWorkflowService(
        repository,
        booking,
        ObjectMapper().findAndRegisterModules(),
        Clock.fixed(now, ZoneOffset.UTC),
    )
    private val doctor = DoctorProfile(
        id = 5,
        user = User(id = 1, passwordHash = "x"),
        firstName = "Ada",
        lastName = "Lovelace",
        timeZone = "Asia/Tashkent",
    )

    @Test
    fun `a selected time becomes a durable proposal and never auto confirms`() {
        `when`(repository.findActive(5, "chat_1", now)).thenReturn(null)
        `when`(repository.update(anyWorkflow(), anyLong())).thenReturn(true)

        val payload = service.attachProposal(
            doctor,
            "chat_1",
            proposalPayload(),
        )

        val inserted = captureInserted()
        assertEquals(BookingWorkflowStatus.COLLECTING_DETAILS, inserted.status)
        val updated = captureLastUpdated()
        assertEquals(BookingWorkflowStatus.AWAITING_CONFIRMATION, updated.status)
        assertNotNull(updated.confirmationToken)
        assertEquals(false, payload["autoConfirm"])
        assertEquals(false, payload["booked"])
        assertEquals("AWAITING_CONFIRMATION", payload["workflowStatus"])
    }

    @Test
    fun `confirmation consumes proposal once and a duplicate returns the completed result`() {
        val pending = pendingWorkflow()
        val patient = PatientProfile(id = 30, fullName = "Franco Herselman")
        val appointment = Appointment(
            id = 77,
            doctor = doctor,
            patient = patient,
            startAt = Instant.parse("2026-10-23T03:00:00Z"),
            endAt = Instant.parse("2026-10-23T03:30:00Z"),
            location = "Clinic",
        )
        `when`(repository.findActive(5, "chat_1", now)).thenReturn(pending, null)
        `when`(repository.update(anyWorkflow(), anyLong())).thenReturn(true)
        `when`(booking.book(anyDoctor(), anyUser(), anyCommand())).thenReturn(appointment)

        val first = service.confirm(doctor, "chat_1")
        val completed = captureLastUpdated()
        `when`(repository.findLatest(5, "chat_1")).thenReturn(completed)
        val replay = service.confirm(doctor, "chat_1")

        assertEquals(true, first["booked"])
        assertEquals(77L, first["appointmentId"])
        assertEquals(true, replay["replayed"])
        verify(booking, times(1)).book(anyDoctor(), anyUser(), anyCommand())
    }

    @Test
    fun `slot loss fails safely instead of authorizing a replacement slot`() {
        val pending = pendingWorkflow()
        `when`(repository.findActive(5, "chat_1", now)).thenReturn(pending)
        `when`(repository.update(anyWorkflow(), anyLong())).thenReturn(true)
        `when`(booking.book(anyDoctor(), anyUser(), anyCommand()))
            .thenThrow(IllegalStateException("Overlapping appointment exists"))

        val result = service.confirm(doctor, "chat_1")

        assertEquals("FAILED", result["workflowStatus"])
        assertFalse(result["booked"] == true)
        assertTrue(result["spokenHint"].toString().contains("could not be booked"))
        assertEquals(BookingWorkflowStatus.FAILED, captureLastUpdated().status)
    }

    @Test
    fun `a concurrent confirmation that loses the version check never books`() {
        val pending = pendingWorkflow()
        `when`(repository.findActive(5, "chat_1", now)).thenReturn(pending)
        `when`(repository.update(anyWorkflow(), anyLong())).thenReturn(false)

        assertThrows(IllegalStateException::class.java) {
            service.confirm(doctor, "chat_1")
        }
        verify(booking, org.mockito.Mockito.never()).book(anyDoctor(), anyUser(), anyCommand())
    }

    @Test
    fun `correcting the time invalidates the old proposal before creating a new one`() {
        val pending = pendingWorkflow()
        `when`(repository.findActive(5, "chat_1", now)).thenReturn(pending)
        `when`(repository.update(anyWorkflow(), anyLong())).thenReturn(true)

        val result = service.attachProposal(
            doctor,
            "chat_1",
            proposalPayload(time = "09:00", startAt = "2026-10-23T04:00:00Z"),
        )

        val captor = ArgumentCaptor.forClass(IntelligenceBookingWorkflow::class.java)
        verify(repository, times(2)).update(captor.capture() ?: pendingWorkflow(), anyLong())
        val invalidated = captor.allValues.first()
        val replacement = captor.allValues.last()
        assertEquals(BookingWorkflowStatus.COLLECTING_DETAILS, invalidated.status)
        assertEquals(null, invalidated.confirmationToken)
        assertEquals(BookingWorkflowStatus.AWAITING_CONFIRMATION, replacement.status)
        assertTrue(replacement.confirmationToken != pending.confirmationToken)
        assertEquals("09:00", (result["preferredSlot"] as Map<*, *>)["startLocal"])
    }

    private fun proposalPayload(
        time: String = "08:00",
        startAt: String = "2026-10-23T03:00:00Z",
    ): Map<String, Any?> = mapOf(
        "cardType" to "propose_appointment",
        "patientId" to 30L,
        "patientName" to "Franco Herselman",
        "date" to "2026-10-23",
        "dateLabel" to "23 Oct 2026",
        "speakableDateLabel" to "Friday, 23 October",
        "requestedTime" to time,
        "preferredSlot" to mapOf(
            "startAt" to startAt,
            "endAt" to Instant.parse(startAt).plusSeconds(1800).toString(),
            "startLocal" to time,
            "slotMinutes" to 30,
        ),
        "slots" to emptyList<Map<String, Any?>>(),
    )

    private fun pendingWorkflow() = IntelligenceBookingWorkflow(
        id = UUID.randomUUID(),
        doctorId = 5,
        conversationId = "chat_1",
        status = BookingWorkflowStatus.AWAITING_CONFIRMATION,
        patientId = 30,
        patientName = "Franco Herselman",
        appointmentDate = LocalDate.parse("2026-10-23"),
        appointmentTime = LocalTime.parse("08:00"),
        proposalJson = ObjectMapper().writeValueAsString(proposalPayload()),
        confirmationToken = UUID.randomUUID(),
        idempotencyKey = UUID.randomUUID(),
        appointmentId = null,
        version = 2,
        expiresAt = now.plusSeconds(600),
    )

    private fun captureInserted(): IntelligenceBookingWorkflow {
        val captor = ArgumentCaptor.forClass(IntelligenceBookingWorkflow::class.java)
        verify(repository).insert(captor.capture() ?: pendingWorkflow())
        return captor.value
    }

    private fun captureLastUpdated(): IntelligenceBookingWorkflow {
        val captor = ArgumentCaptor.forClass(IntelligenceBookingWorkflow::class.java)
        verify(repository, org.mockito.Mockito.atLeastOnce()).update(
            captor.capture() ?: pendingWorkflow(),
            anyLong(),
        )
        return captor.allValues.last()
    }

    private fun anyWorkflow(): IntelligenceBookingWorkflow =
        org.mockito.ArgumentMatchers.any(IntelligenceBookingWorkflow::class.java) ?: pendingWorkflow()

    private fun anyDoctor(): DoctorProfile =
        org.mockito.ArgumentMatchers.any(DoctorProfile::class.java) ?: doctor

    private fun anyUser(): User =
        org.mockito.ArgumentMatchers.any(User::class.java) ?: doctor.user

    private fun anyCommand(): com.shifa.service.DoctorBookingCommand =
        org.mockito.ArgumentMatchers.any(com.shifa.service.DoctorBookingCommand::class.java)
            ?: com.shifa.service.DoctorBookingCommand(30, now, 30)
}
