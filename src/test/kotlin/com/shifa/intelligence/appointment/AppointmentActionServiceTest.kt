package com.shifa.intelligence.appointment

import com.shifa.domain.Appointment
import com.shifa.domain.DoctorProfile
import com.shifa.domain.PatientProfile
import com.shifa.domain.User
import com.shifa.repo.AppointmentRepository
import com.shifa.service.AppointmentCancellationService
import com.shifa.service.AppointmentVisitLifecycleService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class AppointmentActionServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneOffset.UTC)
    private val appointments = mock(AppointmentRepository::class.java)
    private val cancellation = mock(AppointmentCancellationService::class.java)
    private val lifecycle = mock(AppointmentVisitLifecycleService::class.java)
    private val service = AppointmentActionService(
        appointments,
        cancellation,
        lifecycle,
        ClinicTimeRangeResolver(clock),
        PendingAppointmentActionStore(clock),
        clock,
    )
    private val doctor = DoctorProfile(
        id = 5,
        user = User(id = 1, passwordHash = "x"),
        firstName = "Ada",
        lastName = "Lovelace",
        timeZone = "UTC",
    )
    private val patient = PatientProfile(id = 3L, fullName = "Franco")

    @Test
    fun `the cancel request itself does not cancel`() {
        val visit = visit(10L, "2026-10-17T09:00:00Z")
        stubRange(visit)
        val preview = service.previewCancellation(doctor, "Cancel Franco tomorrow", 3L, null)
        assertEquals("AWAITING_CONFIRMATION", preview["status"])
        verifyNoInteractions(cancellation)

        val sameRequest = service.confirmCancellation(
            doctor,
            "Cancel Franco tomorrow",
            listOf(marker(preview)),
        )
        assertEquals("AWAITING_CONFIRMATION", sameRequest["status"])
        verifyNoInteractions(cancellation)
    }

    @Test
    fun `yes after the preview cancels and checks the saved status`() {
        val visit = visit(10L, "2026-10-17T09:00:00Z")
        stubRange(visit)
        `when`(appointments.findById(10L)).thenReturn(Optional.of(visit))
        `when`(cancellation.cancelByDoctorManual(visit)).thenAnswer {
            visit.status = Appointment.Status.CANCELLED
            true
        }
        val preview = service.previewCancellation(doctor, "Cancel Franco tomorrow", 3L, null)
        val result = service.confirmCancellation(doctor, "yes", listOf(marker(preview)))
        assertEquals("SUCCESS", result["status"])
        assertEquals(Appointment.Status.CANCELLED, visit.status)
        verify(cancellation).cancelByDoctorManual(visit)
    }

    @Test
    fun `a changed visit is not cancelled`() {
        val visit = visit(10L, "2026-10-17T09:00:00Z")
        stubRange(visit)
        `when`(appointments.findById(10L)).thenReturn(Optional.of(visit))
        val preview = service.previewCancellation(doctor, "Cancel Franco tomorrow", 3L, null)
        visit.startAt = Instant.parse("2026-10-18T09:00:00Z")
        val result = service.confirmCancellation(doctor, "yes", listOf(marker(preview)))
        assertEquals("FAILURE", result["status"])
        verify(cancellation, never()).cancelByDoctorManual(visit)
    }

    @Test
    fun `another doctors visit is not previewed`() {
        val other = DoctorProfile(
            id = 9,
            user = User(id = 2, passwordHash = "x"),
            firstName = "Other",
            lastName = "Doctor",
            timeZone = "UTC",
        )
        val visit = Appointment(
            id = 10L,
            doctor = other,
            patient = patient,
            startAt = Instant.parse("2026-10-17T09:00:00Z"),
            endAt = Instant.parse("2026-10-17T09:30:00Z"),
            location = "Clinic",
            status = Appointment.Status.CONFIRMED,
        )
        `when`(appointments.findById(10L)).thenReturn(Optional.of(visit))
        val preview = service.previewCancellation(doctor, "Cancel that visit", null, 10L)
        assertEquals("FAILURE", preview["status"])
        verifyNoInteractions(cancellation)
    }

    @Test
    fun `a past visit is left unchanged`() {
        val visit = visit(10L, "2026-10-15T09:00:00Z")
        stubRange(visit)
        val preview = service.previewCancellation(doctor, "Cancel Franco yesterday", 3L, null)
        assertTrue((preview["spokenHint"] as String).contains("Nothing was changed"))
        verifyNoInteractions(cancellation)
    }

    @Test
    fun `bulk cancellation reports a partial failure`() {
        val first = visit(1L, "2026-10-20T09:00:00Z")
        val second = visit(2L, "2026-10-21T09:00:00Z")
        `when`(appointments.findByDoctorIdAndStartAtBetween(anyLong(), anyInstant(), anyInstant()))
            .thenReturn(listOf(first, second))
        `when`(appointments.findById(1L)).thenReturn(Optional.of(first))
        `when`(appointments.findById(2L)).thenReturn(Optional.of(second))
        `when`(cancellation.cancelByDoctorManual(first)).thenAnswer {
            first.status = Appointment.Status.CANCELLED
            true
        }
        `when`(cancellation.cancelByDoctorManual(second)).thenReturn(false)
        val preview = service.previewCancellation(doctor, "Cancel all my appointments next week", null, null)
        assertEquals(2, preview["count"])
        val result = service.confirmCancellation(doctor, "yes cancel them", listOf(marker(preview)))
        assertEquals("PARTIAL_SUCCESS", result["status"])
    }

    @Test
    fun `one of next week asks which visit instead of cancelling them all`() {
        val first = visit(1L, "2026-10-20T09:00:00Z")
        val second = visit(2L, "2026-10-21T09:00:00Z")
        `when`(appointments.findByDoctorIdAndStartAtBetween(anyLong(), anyInstant(), anyInstant()))
            .thenReturn(listOf(first, second))
        val preview = service.previewCancellation(
            doctor,
            "cancel one of the appointments from next week",
            null,
            null,
        )
        assertEquals("AWAITING_CLARIFICATION", preview["status"])
        assertEquals(null, preview["actionContext"])
        verifyNoInteractions(cancellation)
    }

    @Test
    fun `an already cancelled visit is not offered again`() {
        val open = visit(1L, "2026-10-20T09:00:00Z")
        val second = visit(2L, "2026-10-21T09:00:00Z")
        val third = visit(3L, "2026-10-22T09:00:00Z")
        val cancelled = visit(1211L, "2026-10-23T04:00:00Z")
        cancelled.status = Appointment.Status.CANCELLED
        `when`(appointments.findByDoctorIdAndStartAtBetween(anyLong(), anyInstant(), anyInstant()))
            .thenReturn(listOf(open, second, third, cancelled))
        val preview = service.previewCancellation(
            doctor,
            "cancel appointment for next week",
            null,
            null,
        )
        assertEquals("AWAITING_CLARIFICATION", preview["status"])
        @Suppress("UNCHECKED_CAST")
        val listed = preview["appointments"] as List<Map<String, Any?>>
        assertEquals(3, listed.size)
        assertTrue((preview["spokenHint"] as String).contains("already cancelled"))
        verifyNoInteractions(cancellation)
    }

    @Test
    fun `one visit next week is previewed for a single cancellation`() {
        val only = visit(1211L, "2026-10-23T04:00:00Z")
        `when`(appointments.findByDoctorIdAndStartAtBetween(anyLong(), anyInstant(), anyInstant()))
            .thenReturn(listOf(only))
        val preview = service.previewCancellation(
            doctor,
            "cancel one of the appointments from next week",
            null,
            null,
        )
        assertEquals("AWAITING_CONFIRMATION", preview["status"])
        assertEquals(1, preview["count"])
    }

    @Test
    fun `opening two visits asks which to open and never offers cancellation`() {
        val today = visit(1217L, "2026-10-20T10:00:00Z")
        val later = visit(1216L, "2026-10-23T08:00:00Z")
        `when`(appointments.findByDoctorIdAndStartAtBetween(anyLong(), anyInstant(), anyInstant()))
            .thenReturn(listOf(today, later))

        val result = service.openAppointment(
            doctor,
            "Ha, aynan shunday.",
            3L,
            null,
            "appointment",
            false,
        )

        assertEquals("AWAITING_CLARIFICATION", result["status"])
        assertEquals("OPEN_APPOINTMENT", result["operation"])
        assertTrue((result["spokenHint"] as String).contains("open"))
        assertFalse((result["spokenHint"] as String).lowercase().contains("cancel"))
        verifyNoInteractions(cancellation)
    }

    @Test
    fun `a spoken clock time selects that visit when opening`() {
        val ten = visit(1217L, "2026-10-20T10:00:00Z")
        val eight = visit(1216L, "2026-10-23T08:00:00Z")
        `when`(appointments.findByDoctorIdAndStartAtBetween(anyLong(), anyInstant(), anyInstant()))
            .thenReturn(listOf(ten, eight))
        `when`(lifecycle.markNoShowIfDue(ten)).thenReturn(ten)
        `when`(lifecycle.openPayloadFields(ten, java.time.ZoneId.of("UTC")))
            .thenReturn(mapOf("patientName" to "Franco", "status" to "CONFIRMED", "route" to "/visit"))

        val result = service.openAppointment(
            doctor,
            "Open the 10:00 appointment",
            3L,
            null,
            "appointment",
            false,
        )

        assertEquals(1217L, result["appointmentId"])
        assertEquals("SUCCESS", result["status"])
        verifyNoInteractions(cancellation)
    }

    @Test
    fun `another doctors appointment cannot be opened`() {
        val otherDoctor = DoctorProfile(
            id = 9,
            user = User(id = 2, passwordHash = "x"),
            firstName = "Other",
            lastName = "Doctor",
            timeZone = "UTC",
        )
        val foreign = Appointment(
            id = 999L,
            doctor = otherDoctor,
            patient = patient,
            startAt = Instant.parse("2026-10-20T10:00:00Z"),
            endAt = Instant.parse("2026-10-20T10:30:00Z"),
            location = "Clinic",
            status = Appointment.Status.CONFIRMED,
        )
        `when`(appointments.findById(999L)).thenReturn(Optional.of(foreign))

        val result = service.openAppointment(doctor, "open it", 3L, 999L, "appointment", false)

        assertEquals("FAILURE", result["status"])
        assertEquals(null, result["appointment"])
        verifyNoInteractions(lifecycle)
    }

    @Test
    fun `confirmation phrases do not include the original request`() {
        assertFalse(AppointmentActionService.isExplicitConfirmation("Cancel all my appointments next week"))
        assertTrue(AppointmentActionService.isExplicitConfirmation("yes"))
        assertTrue(AppointmentActionService.isExplicitConfirmation("Yes, cancel them."))
        assertTrue(AppointmentActionService.isExplicitConfirmation("Ha."))
        assertTrue(AppointmentActionService.isExplicitConfirmation("ha bekor qiling"))
        assertFalse(AppointmentActionService.isExplicitConfirmation("23-oktabr kunga barcha qabullarni bekor qil."))
    }

    private fun visit(id: Long, start: String): Appointment = Appointment(
        id = id,
        doctor = doctor,
        patient = patient,
        startAt = Instant.parse(start),
        endAt = Instant.parse(start).plusSeconds(1800),
        location = "Clinic",
        status = Appointment.Status.CONFIRMED,
    )

    private fun stubRange(visit: Appointment) {
        `when`(appointments.findByDoctorIdAndStartAtBetween(anyLong(), anyInstant(), anyInstant()))
            .thenReturn(listOf(visit))
    }

    @Suppress("UNCHECKED_CAST")
    private fun marker(preview: Map<String, Any?>): String {
        val token = (preview["actionContext"] as Map<String, Any?>)["token"]
        return "I can cancel that visit.\n<appointment_action>$token</appointment_action>"
    }

    private fun anyInstant(): Instant =
        org.mockito.ArgumentMatchers.any(Instant::class.java) ?: Instant.EPOCH
}
