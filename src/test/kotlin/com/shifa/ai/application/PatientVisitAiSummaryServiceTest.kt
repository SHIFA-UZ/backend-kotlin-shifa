package com.shifa.ai.application

import com.shifa.ai.infrastructure.OpenAiResponsesService

import com.shifa.shared.config.OpenAiProperties
import com.shifa.appointments.domain.Appointment
import com.shifa.patients.domain.PatientProfile
import com.shifa.ai.domain.PatientVisitAiSummary
import com.shifa.appointments.infrastructure.AppointmentRepository
import com.shifa.appointments.infrastructure.ConsultationNoteRepository
import com.shifa.notifications.application.NotificationService
import com.shifa.ai.infrastructure.PatientVisitAiSummaryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger

class PatientVisitAiSummaryServiceTest {
    private val appointments = mock(com.shifa.appointments.application.Appointments::class.java)
    private val notes = mock(com.shifa.appointments.application.ConsultationNotes::class.java)
    private val summaries = mock(PatientVisitAiSummaryRepository::class.java)
    private val notificationService = mock(NotificationService::class.java)
    private val openAi = mock(OpenAiResponsesService::class.java)
    private val startedJobs = AtomicInteger(0)
    private val savedRows = AtomicInteger(0)
    private val props = OpenAiProperties().apply { model = "gpt-test" }

    private val service = PatientVisitAiSummaryService(
        appointments,
        notes,
        summaries,
        notificationService,
        openAi,
        props,
        { startedJobs.incrementAndGet() },
    )

    @Test
    fun `does not start another job when summary is already ready`() {
        stubAppointment()
        `when`(summaries.findByAppointmentIdAndLanguage(42L, "uz")).thenReturn(
            PatientVisitAiSummary(
                appointmentId = 42L,
                patientId = 9L,
                language = "uz",
                status = PatientVisitAiSummary.Status.READY,
                modelVersion = "gpt-test",
            )
        )

        service.enqueueGeneration(42L, "uz", force = false)
        service.enqueueGeneration(42L, "uz", force = false)

        assertEquals(0, startedJobs.get())
        assertEquals(0, savedRows.get())
    }

    @Test
    fun `does not start another job while generation is queued`() {
        stubAppointment()
        `when`(summaries.findByAppointmentIdAndLanguage(42L, "uz")).thenReturn(
            PatientVisitAiSummary(
                appointmentId = 42L,
                patientId = 9L,
                language = "uz",
                status = PatientVisitAiSummary.Status.QUEUED,
                modelVersion = "gpt-test",
            )
        )

        service.enqueueGeneration(42L, "uz", force = false)

        assertEquals(0, startedJobs.get())
    }

    @Test
    fun `starts generation only once when called twice before a row exists`() {
        stubAppointment()
        `when`(summaries.findByAppointmentIdAndLanguage(42L, "uz")).thenReturn(null)
        `when`(summaries.save(anyValue())).thenAnswer {
            savedRows.incrementAndGet()
            it.arguments[0]
        }

        service.enqueueGeneration(42L, "uz", force = false)
        service.enqueueGeneration(42L, "uz", force = false)

        assertEquals(1, startedJobs.get())
        assertEquals(1, savedRows.get())
    }

    private fun stubAppointment() {
        val appointment = mock(Appointment::class.java)
        val patient = mock(PatientProfile::class.java)
        `when`(appointment.id).thenReturn(42L)
        `when`(appointment.patient).thenReturn(patient)
        `when`(patient.id).thenReturn(9L)
        `when`(patient.language).thenReturn("uz")
        `when`(appointments.findByIdWithDoctorAndPatient(42L)).thenReturn(Optional.of(appointment))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyValue(): T {
        Mockito.any<T>()
        return null as T
    }
}
