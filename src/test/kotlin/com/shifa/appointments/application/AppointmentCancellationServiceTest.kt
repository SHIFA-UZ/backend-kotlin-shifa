package com.shifa.appointments.application

import com.shifa.appointments.domain.Appointment
import com.shifa.appointments.infrastructure.AppointmentRepository
import com.shifa.auth.domain.User
import com.shifa.clinics.application.TreatmentPlanFulfillmentService
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.notifications.application.NotificationService
import com.shifa.notifications.domain.Notification
import com.shifa.patients.domain.PatientProfile
import com.shifa.shared.CodedStatusException
import com.shifa.shared.ErrorCode
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class AppointmentCancellationServiceTest {
    private val appts = mock(AppointmentRepository::class.java)
    private val notifications = mock(NotificationService::class.java)
    private val service = AppointmentCancellationService(appts, notifications, mock(TreatmentPlanFulfillmentService::class.java))
    private val doctor = DoctorProfile(id = 5, user = User(id = 1, passwordHash = "x"), firstName = "Ada", lastName = "L", timeZone = "UTC")

    private fun visit(start: Instant, status: Appointment.Status = Appointment.Status.CONFIRMED, lang: String? = null) = Appointment(
        id = 10,
        doctor = doctor,
        patient = PatientProfile(id = 3L, fullName = "Franco").apply { language = lang; fcmToken = "tok" },
        startAt = start,
        endAt = start.plus(Duration.ofMinutes(30)),
        location = "Clinic",
        status = status,
    )

    private val placeholder = visit(Instant.EPOCH)
    private val placeholderNotification = Notification(title = "", message = "", type = Notification.Type.GENERAL)
    private fun anyAppointment(): Appointment = any(Appointment::class.java) ?: placeholder
    private fun anyNotification(): Notification = any(Notification::class.java) ?: placeholderNotification

    init {
        `when`(appts.save(anyAppointment())).thenAnswer { it.arguments[0] }
        `when`(notifications.save(anyNotification())).thenAnswer { it.arguments[0] }
    }

    private fun savedNotification(): Notification {
        val captor = ArgumentCaptor.forClass(Notification::class.java)
        verify(notifications).save(captor.capture() ?: placeholderNotification)
        return captor.value
    }

    @Test
    fun `a reason is stored and told to the patient in their language`() {
        val visit = visit(Instant.parse("2099-11-07T09:00:00Z"), lang = "ru")

        assertTrue(service.cancelByDoctorManual(visit, Appointment.CancelReason.DOCTOR_UNAVAILABLE, "  Заболел  "))

        assertEquals(Appointment.Status.CANCELLED, visit.status)
        assertEquals(Appointment.CancelReason.DOCTOR_UNAVAILABLE, visit.cancelReasonCode)
        assertEquals("Заболел", visit.cancelNote)
        val n = savedNotification()
        assertEquals(Notification.Type.APPOINTMENT_CANCELLED, n.type)
        assertEquals("DOCTOR_UNAVAILABLE", n.cancelReasonCode)
        assertEquals("Заболел", n.cancelNote)
        assertEquals("Запись отменена", n.title)
        assertEquals("Врач отменил вашу запись на 7 ноября 2099. Пожалуйста, запишитесь снова. Причина: врач недоступен.", n.message)
        verify(notifications).sendPatientNotificationAfterCommit("tok", n, emptyMap())
    }

    @Test
    fun `without a reason the notification is the English text as before`() {
        val visit = visit(Instant.parse("2099-11-07T09:00:00Z"), lang = "uz")

        assertTrue(service.cancelByDoctorManual(visit))

        assertNull(visit.cancelReasonCode)
        val n = savedNotification()
        assertEquals("Appointment Cancelled", n.title)
        assertEquals("Doctor has cancelled your appointment on 7 November 2099. Please make another appointment.", n.message)
        assertNull(n.cancelReasonCode)
    }

    @Test
    fun `a no-show tells the patient unless notify is false`() {
        val now = Instant.parse("2026-11-07T09:10:00Z")
        val visit = visit(Instant.parse("2026-11-07T09:00:00Z"), lang = "uz")

        service.markNoShow(visit, notify = true, now = now)

        assertEquals(Appointment.Status.NO_SHOW, visit.status)
        val n = savedNotification()
        assertEquals(Notification.Type.APPOINTMENT_NO_SHOW, n.type)
        assertEquals(10L, n.appointmentId)
        assertTrue(n.message.startsWith("7 noyabr 2026 kungi qabulingiz"), n.message)
        verify(notifications).sendPatientNotificationAfterCommit("tok", n, emptyMap())
    }

    @Test
    fun `notify false marks the no-show silently`() {
        val visit = visit(Instant.parse("2026-11-07T09:00:00Z"), status = Appointment.Status.IN_PROGRESS)

        service.markNoShow(visit, notify = false, now = Instant.parse("2026-11-07T09:10:00Z"))

        assertEquals(Appointment.Status.NO_SHOW, visit.status)
        verifyNoInteractions(notifications)
    }

    @Test
    fun `a no-show outside the window or status is 409 NO_SHOW_NOT_ALLOWED and changes nothing`() {
        val start = Instant.parse("2026-11-07T09:00:00Z")
        val tooEarly = visit(start)
        val e = assertThrows(CodedStatusException::class.java) { service.markNoShow(tooEarly, true, start.minusSeconds(1)) }
        assertEquals(409, e.statusCode.value())
        assertEquals(ErrorCode.NO_SHOW_NOT_ALLOWED, e.code)
        assertEquals(Appointment.Status.CONFIRMED, tooEarly.status)
        verify(appts, never()).save(anyAppointment())
        verify(notifications, never()).sendPatientNotificationAfterCommit(any(), anyNotification(), anyMap())
    }

    @Test
    fun `the no-show window runs from the start to 24 hours after the end`() {
        val start = Instant.parse("2026-11-07T09:00:00Z")
        val end = start.plus(Duration.ofMinutes(30))
        val v = visit(start)
        assertFalse(v.canMarkNoShow(start.minusMillis(1)))
        assertTrue(v.canMarkNoShow(start))
        assertTrue(v.canMarkNoShow(end.plus(Duration.ofHours(24))))
        assertFalse(v.canMarkNoShow(end.plus(Duration.ofHours(24)).plusMillis(1)))
        listOf(Appointment.Status.REQUESTED, Appointment.Status.CANCELLED, Appointment.Status.COMPLETED, Appointment.Status.NO_SHOW)
            .forEach { assertFalse(visit(start, it).canMarkNoShow(start), it.name) }
    }
}
