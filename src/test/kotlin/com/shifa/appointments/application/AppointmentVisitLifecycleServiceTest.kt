package com.shifa.appointments.application

import com.shifa.ai.application.AiDraftNoteService
import com.shifa.ai.application.ClinicalRagIndexingService
import com.shifa.ai.application.PatientVisitAiSummaryService
import com.shifa.ai.application.ScribePipelineService
import com.shifa.ai.application.ScribeRecordingService
import com.shifa.clinics.application.TreatmentPlanStatusService

import com.shifa.appointments.domain.Appointment
import com.shifa.appointments.domain.ConsultationNote
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.notifications.domain.Notification
import com.shifa.patients.domain.PatientProfile
import com.shifa.auth.domain.User
import com.shifa.appointments.infrastructure.AppointmentRepository
import com.shifa.appointments.infrastructure.ConsultationNoteRepository
import com.shifa.notifications.application.NotificationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import tools.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class AppointmentVisitLifecycleServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneOffset.UTC)
    private val appointments = mock(AppointmentRepository::class.java)
    private val consultationNotes = mock(ConsultationNoteRepository::class.java)
    private val notificationService = mock(NotificationService::class.java)
    private val drafts = mock(AiDraftNoteService::class.java)
    private val documentation = mock(AppointmentDocumentationService::class.java)
    private val visitSummary = mock(PatientVisitAiSummaryService::class.java)
    private val treatmentPlans = mock(TreatmentPlanStatusService::class.java)
    private val rag = mock(ClinicalRagIndexingService::class.java)
    private val scribe = mock(ScribePipelineService::class.java)
    private val recordings = mock(ScribeRecordingService::class.java)

    private lateinit var service: AppointmentVisitLifecycleService

    private val doctor = DoctorProfile(
        id = 5,
        user = User(id = 1, passwordHash = "x"),
        firstName = "Ada",
        lastName = "Lovelace",
        timeZone = "UTC",
    )
    private val otherDoctor = DoctorProfile(
        id = 9,
        user = User(id = 2, passwordHash = "x"),
        firstName = "Other",
        lastName = "Doctor",
        timeZone = "UTC",
    )
    private val patient = PatientProfile(id = 3L, fullName = "Franco", fcmToken = "token")

    @BeforeEach
    fun setUp() {
        service = AppointmentVisitLifecycleService(
            appointments,
            consultationNotes,
            notificationService,
            drafts,
            documentation,
            visitSummary,
            treatmentPlans,
            rag,
            scribe,
            recordings,
            clock,
            ObjectMapper(),
        )
        `when`(appointments.save(anyAppointment())).thenAnswer { it.arguments[0] }
        `when`(consultationNotes.save(anyNote())).thenAnswer { it.arguments[0] }
        `when`(notificationService.save(anyNotification())).thenAnswer { it.arguments[0] }
        `when`(recordings.listRecordingsForRoom(org.mockito.ArgumentMatchers.anyString())).thenReturn(emptyList())
        `when`(documentation.statusFor(org.mockito.ArgumentMatchers.anyLong())).thenReturn(
            AppointmentDocumentationService.ScribeStatus("none", false, false),
        )
    }

    @Test
    fun `starts a non-video visit scheduled today`() {
        val visit = visit(start = "2026-10-16T14:00:00Z")
        stubFind(visit)
        val result = service.start(doctor, visit.id)
        assertTrue(result is AppointmentVisitLifecycleService.Outcome.Ok)
        assertEquals(Appointment.Status.IN_PROGRESS, visit.status)
    }

    @Test
    fun `starts a non-video visit that began two hours ago`() {
        val visit = visit(start = "2026-10-16T08:00:00Z")
        stubFind(visit)
        val result = service.start(doctor, visit.id)
        assertTrue(result is AppointmentVisitLifecycleService.Outcome.Ok)
        assertEquals(Appointment.Status.IN_PROGRESS, visit.status)
    }

    @Test
    fun `refuses a visit the day after tomorrow`() {
        val visit = visit(start = "2026-10-18T09:00:00Z")
        stubFind(visit)
        val result = service.start(doctor, visit.id) as AppointmentVisitLifecycleService.Outcome.Failed
        assertEquals(Appointment.Status.CONFIRMED, visit.status)
        assertTrue(result.message.contains("today or tomorrow"))
    }

    @Test
    fun `a visit 25 hours ago still confirmed becomes no-show and does not start`() {
        val visit = visit(start = "2026-10-15T08:00:00Z")
        stubFind(visit)
        val result = service.start(doctor, visit.id) as AppointmentVisitLifecycleService.Outcome.Failed
        assertEquals(Appointment.Status.NO_SHOW, visit.status)
        assertTrue(result.message.contains("no-show"))
    }

    @Test
    fun `in progress visit after 24 hours can still be opened and is not marked no-show`() {
        val visit = visit(start = "2026-10-15T08:00:00Z", status = Appointment.Status.IN_PROGRESS)
        stubFind(visit)
        val marked = service.markNoShowIfDue(visit)
        assertEquals(Appointment.Status.IN_PROGRESS, marked.status)
        val started = service.start(doctor, visit.id)
        assertTrue(started is AppointmentVisitLifecycleService.Outcome.Ok)
        assertEquals(Appointment.Status.IN_PROGRESS, visit.status)
    }

    @Test
    fun `complete only from in progress`() {
        val confirmed = visit(start = "2026-10-16T09:00:00Z")
        stubFind(confirmed)
        val refused = service.completeFromIntelligence(doctor, confirmed.id)
        assertTrue(refused is AppointmentVisitLifecycleService.Outcome.Failed)

        val inProgress = visit(start = "2026-10-16T09:00:00Z", status = Appointment.Status.IN_PROGRESS)
        stubFind(inProgress)
        `when`(scribe.isScribeInFlight(inProgress.id)).thenReturn(false)
        val ok = service.completeFromIntelligence(doctor, inProgress.id)
        assertTrue(ok is AppointmentVisitLifecycleService.Outcome.Ok)
        assertEquals(Appointment.Status.COMPLETED, inProgress.status)
    }

    @Test
    fun `signature sets signatureRequested and is not implied by note append`() {
        val visit = visit(start = "2026-10-16T09:00:00Z", status = Appointment.Status.IN_PROGRESS)
        stubFind(visit)
        `when`(consultationNotes.findByAppointmentIdOrderByCreatedAtAsc(visit.id)).thenReturn(emptyList())

        service.appendConsultationNote(doctor, visit.id, "Patient reports mild pain.")
        assertFalse(visit.signatureRequested)
        verifyNoInteractions(notificationService)
        verify(notificationService, org.mockito.Mockito.never())
            .sendPatientNotification(org.mockito.ArgumentMatchers.any(), anyNotification(), org.mockito.ArgumentMatchers.anyMap())

        val signed = service.requestSignature(doctor, visit.id)
        assertTrue(signed is AppointmentVisitLifecycleService.Outcome.Ok)
        assertTrue(visit.signatureRequested)
        verify(notificationService).save(anyNotification())
        verify(notificationService).sendPatientNotification(
            org.mockito.ArgumentMatchers.eq("token"),
            anyNotification(),
            org.mockito.ArgumentMatchers.anyMap(),
        )
    }

    @Test
    fun `intelligence note is copied into dental documentation notes`() {
        val visit = visit(start = "2026-10-16T09:00:00Z", status = Appointment.Status.IN_PROGRESS)
        stubFind(visit)
        `when`(consultationNotes.findByAppointmentIdOrderByCreatedAtAsc(visit.id)).thenReturn(emptyList())

        service.appendConsultationNote(doctor, visit.id, "Patient reports mild pain.")

        assertTrue(visit.dentalDocumentation!!.contains("Patient reports mild pain."))
    }

    @Test
    fun `upsert replaces the manual note instead of appending`() {
        val visit = visit(start = "2026-10-16T09:00:00Z", status = Appointment.Status.IN_PROGRESS)
        stubFind(visit)
        val existing = ConsultationNote(
            id = 44L,
            doctorId = doctor.id,
            patientId = patient.id!!,
            appointmentId = visit.id,
            body = "First line.",
            source = "MANUAL",
        )
        `when`(consultationNotes.findByAppointmentIdOrderByCreatedAtAsc(visit.id)).thenReturn(listOf(existing))

        val updated = service.upsertManualConsultationNote(doctor, visit.id, "First line.\n\nAdded from calendar.")
        assertEquals("First line.\n\nAdded from calendar.", updated!!.note.body)
        assertEquals(44L, updated.note.id)
    }

    @Test
    fun `append does not write into an AI draft note`() {
        val visit = visit(start = "2026-10-16T09:00:00Z", status = Appointment.Status.IN_PROGRESS)
        stubFind(visit)
        val ai = ConsultationNote(
            id = 8L,
            doctorId = doctor.id,
            patientId = patient.id!!,
            appointmentId = visit.id,
            body = "Scribe summary.",
            source = "AI_DRAFT",
        )
        `when`(consultationNotes.findByAppointmentIdOrderByCreatedAtAsc(visit.id)).thenReturn(listOf(ai))

        val saved = service.appendConsultationNote(doctor, visit.id, "Doctor addendum.")
        assertEquals("Doctor addendum.", saved!!.note.body)
        assertEquals("MANUAL", saved.note.source)
        assertEquals("Scribe summary.", ai.body)
    }

    @Test
    fun `note append updates the same consultation note`() {
        val visit = visit(start = "2026-10-16T09:00:00Z", status = Appointment.Status.IN_PROGRESS)
        stubFind(visit)
        val existing = ConsultationNote(
            id = 44L,
            doctorId = doctor.id,
            patientId = patient.id!!,
            appointmentId = visit.id,
            body = "First line.",
            source = "MANUAL",
        )
        `when`(consultationNotes.findByAppointmentIdOrderByCreatedAtAsc(visit.id)).thenReturn(listOf(existing))

        val first = service.appendConsultationNote(doctor, visit.id, "Second line.")
        assertEquals("First line.\n\nSecond line.", first!!.note.body)
        assertEquals(44L, first.note.id)

        `when`(consultationNotes.findByAppointmentIdOrderByCreatedAtAsc(visit.id)).thenReturn(listOf(existing))
        val second = service.appendConsultationNote(doctor, visit.id, "Third line.")
        assertEquals("First line.\n\nSecond line.\n\nThird line.", second!!.note.body)
        assertEquals(44L, second.note.id)
    }

    @Test
    fun `cancelled and completed visits cannot be started`() {
        val cancelled = visit(start = "2026-10-16T09:00:00Z", status = Appointment.Status.CANCELLED)
        stubFind(cancelled)
        val cancelledResult = service.start(doctor, cancelled.id) as AppointmentVisitLifecycleService.Outcome.Failed
        assertTrue(cancelledResult.message.contains("cancelled"))

        val completed = visit(start = "2026-10-16T09:00:00Z", status = Appointment.Status.COMPLETED)
        stubFind(completed)
        val completedResult = service.start(doctor, completed.id) as AppointmentVisitLifecycleService.Outcome.Failed
        assertTrue(completedResult.message.contains("completed"))
    }

    @Test
    fun `a no-show cannot be started or completed`() {
        val visit = visit(start = "2026-10-16T09:00:00Z", status = Appointment.Status.NO_SHOW)
        stubFind(visit)
        val started = service.start(doctor, visit.id) as AppointmentVisitLifecycleService.Outcome.Failed
        assertTrue(started.message.contains("no-show"))
        val completed = service.completeFromIntelligence(doctor, visit.id) as AppointmentVisitLifecycleService.Outcome.Failed
        assertTrue(completed.message.contains("in progress"))
        verify(documentation, never()).generateForAppointment(org.mockito.ArgumentMatchers.anyLong())
    }

    @Test
    fun `repeated completion does not run documentation again`() {
        val visit = visit(start = "2026-10-16T09:00:00Z", status = Appointment.Status.IN_PROGRESS)
        stubFind(visit)
        service.completeFromIntelligence(doctor, visit.id)
        service.completeFromIntelligence(doctor, visit.id)
        verify(documentation, times(1)).generateForAppointment(visit.id)
    }

    @Test
    fun `documentation failure still leaves the visit completed`() {
        val visit = visit(start = "2026-10-16T09:00:00Z", status = Appointment.Status.IN_PROGRESS)
        stubFind(visit)
        `when`(documentation.generateForAppointment(visit.id)).thenThrow(RuntimeException("pdf"))
        val result = service.completeFromIntelligence(doctor, visit.id)
        assertTrue(result is AppointmentVisitLifecycleService.Outcome.Ok)
        assertEquals(Appointment.Status.COMPLETED, visit.status)
    }

    @Test
    fun `a repeated signature request does not send another notification`() {
        val visit = visit(start = "2026-10-16T09:00:00Z", status = Appointment.Status.IN_PROGRESS)
        stubFind(visit)
        service.requestSignature(doctor, visit.id)
        val second = service.requestSignature(doctor, visit.id) as AppointmentVisitLifecycleService.Outcome.Ok
        assertTrue(second.message!!.contains("already requested"))
        verify(notificationService, times(1)).save(anyNotification())
    }

    @Test
    fun `a push failure still keeps the signature request`() {
        val visit = visit(start = "2026-10-16T09:00:00Z", status = Appointment.Status.IN_PROGRESS)
        stubFind(visit)
        `when`(
            notificationService.sendPatientNotification(
                org.mockito.ArgumentMatchers.anyString(),
                anyNotification(),
                org.mockito.ArgumentMatchers.anyMap(),
            ),
        ).thenThrow(RuntimeException("push"))
        val result = service.requestSignature(doctor, visit.id)
        assertTrue(result is AppointmentVisitLifecycleService.Outcome.Ok)
        assertTrue(visit.signatureRequested)
        verify(notificationService).save(anyNotification())
    }

    @Test
    fun `the same dictated text is not appended twice`() {
        val visit = visit(start = "2026-10-16T09:00:00Z", status = Appointment.Status.IN_PROGRESS)
        stubFind(visit)
        val existing = ConsultationNote(
            id = 44L,
            doctorId = doctor.id,
            patientId = patient.id!!,
            appointmentId = visit.id,
            body = "Patient reports mild pain.",
            source = "MANUAL",
        )
        `when`(consultationNotes.findByAppointmentIdOrderByCreatedAtAsc(visit.id)).thenReturn(listOf(existing))
        val again = service.appendConsultationNote(doctor, visit.id, "Patient reports mild pain.")
        assertEquals(false, again!!.appended)
        assertEquals("Patient reports mild pain.", again.note.body)
        verify(consultationNotes, never()).save(anyNote())
    }

    @Test
    fun `a signed visit note cannot be changed`() {
        val visit = visit(start = "2026-10-16T09:00:00Z", status = Appointment.Status.COMPLETED)
        visit.patientSignedAt = Instant.parse("2026-10-16T09:30:00Z")
        stubFind(visit)
        val error = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            service.appendConsultationNote(doctor, visit.id, "Add a diagnosis.")
        }
        assertTrue(error.message!!.contains("already signed"))
        verify(consultationNotes, never()).save(anyNote())
    }

    @Test
    fun `another doctors appointment is rejected`() {
        val visit = visit(start = "2026-10-16T09:00:00Z", doctor = otherDoctor)
        stubFind(visit)
        val start = service.start(doctor, visit.id) as AppointmentVisitLifecycleService.Outcome.Failed
        assertTrue(start.message.contains("could not find"))
        assertNull(service.appendConsultationNote(doctor, visit.id, "Should not save"))
        val complete = service.completeFromIntelligence(doctor, visit.id) as AppointmentVisitLifecycleService.Outcome.Failed
        assertTrue(complete.message.contains("could not find"))
        verify(appointments, never()).save(anyAppointment())
    }

    private fun visit(
        start: String,
        status: Appointment.Status = Appointment.Status.CONFIRMED,
        doctor: DoctorProfile = this.doctor,
        location: String = "Clinic",
    ): Appointment = Appointment(
        id = 10L,
        doctor = doctor,
        patient = patient,
        startAt = Instant.parse(start),
        endAt = Instant.parse(start).plusSeconds(1800),
        location = location,
        status = status,
    )

    private fun stubFind(visit: Appointment) {
        `when`(appointments.findById(visit.id)).thenReturn(Optional.of(visit))
        `when`(appointments.findByIdForUpdate(visit.id)).thenReturn(Optional.of(visit))
    }

    private fun anyAppointment(): Appointment =
        any(Appointment::class.java) ?: visit(start = "2026-10-16T09:00:00Z")

    private fun anyNote(): ConsultationNote =
        any(ConsultationNote::class.java)
            ?: ConsultationNote(doctorId = doctor.id, patientId = 3L, body = "x")

    private fun anyNotification(): Notification =
        any(Notification::class.java)
            ?: Notification(
                patient = patient,
                title = "t",
                message = "m",
                type = Notification.Type.SIGNATURE_REQUESTED,
            )
}
