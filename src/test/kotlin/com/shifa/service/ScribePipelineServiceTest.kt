package com.shifa.service

import com.shifa.domain.AiDraftNote
import com.shifa.domain.Appointment
import com.shifa.domain.ConsultationNote
import com.shifa.domain.DoctorProfile
import com.shifa.domain.Notification
import com.shifa.domain.PatientProfile
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.NotificationRepository
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.atomic.AtomicBoolean

class ScribePipelineServiceTest {
    private val recordings = mock(ScribeRecordingService::class.java)
    private val transcription = mock(TranscriptionService::class.java)
    private val summaries = mock(MedicalSummaryService::class.java)
    private val drafts = mock(AiDraftNoteService::class.java)
    private val diagnoses = mock(DiagnosisSuggestionService::class.java)
    private val appointments = mock(AppointmentRepository::class.java)
    private val notifications = mock(NotificationRepository::class.java)
    private val doctors = mock(DoctorProfileRepository::class.java)
    private val fcm = mock(FcmService::class.java)
    private val docs = mock(AppointmentDocumentationService::class.java)
    private val visitSummaries = mock(PatientVisitAiSummaryService::class.java)
    private val visitFacts = mock(VisitFactService::class.java)

    private val service = ScribePipelineService(
        recordings,
        transcription,
        summaries,
        drafts,
        diagnoses,
        appointments,
        notifications,
        doctors,
        fcm,
        docs,
        visitSummaries,
        visitFacts,
    )

    @Test
    fun `does not download or transcribe when a scribe draft already exists`() {
        stubAppointment(42L)
        `when`(drafts.hasActiveScribeDraft(42L, ScribePipelineService.SCRIBE_LABEL)).thenReturn(true)
        `when`(drafts.hasGeneratedScribeDraft(42L, ScribePipelineService.SCRIBE_LABEL)).thenReturn(false)

        service.processVideoRecording("appointment-42", "rec-1")
        service.processVideoRecording("appointment-42", "rec-2")

        verify(recordings, never()).downloadDailyRecording("rec-1")
        verify(recordings, never()).downloadDailyRecording("rec-2")
        Mockito.verifyNoInteractions(transcription)
    }

    @Test
    fun `same recording is processed only once`() {
        val audio = Path.of("audio.wav")
        stubSuccessfulPipeline(42L, audio)
        `when`(recordings.downloadDailyRecording("rec-1")).thenReturn(audio)
        `when`(recordings.ensureAudioFormat(audio)).thenReturn(audio)

        service.processVideoRecording("appointment-42", "rec-1")
        service.processVideoRecording("appointment-42", "rec-1")

        verify(recordings, times(1)).downloadDailyRecording("rec-1")
        verify(transcription, times(1)).transcribe(
            audio,
            "uz",
            TranscriptionPurpose.SCRIBE_PIPELINE,
        )
    }

    @Test
    fun `doctor is notified only once even if finalize confirms again`() {
        stubAppointment(42L)
        val doctor = mock(DoctorProfile::class.java)
        `when`(doctor.fcmToken).thenReturn("token")
        `when`(doctors.findById(7L)).thenReturn(Optional.of(doctor))
        val note = ConsultationNote(doctorId = 7L, patientId = 9L, appointmentId = 42L, body = "note")
        `when`(drafts.confirmGeneratedScribeDrafts(42L, 7L, ScribePipelineService.SCRIBE_LABEL))
            .thenReturn(listOf(note))
        `when`(
            notifications.existsByDoctor_IdAndAppointmentIdAndType(
                7L,
                42L,
                Notification.Type.AI_SCRIBE_READY,
            )
        ).thenReturn(false, true)
        val saved = mutableListOf<Notification>()
        `when`(notifications.save(anyValue())).thenAnswer { invocation ->
            val notif = invocation.arguments[0] as Notification
            saved += notif
            notif
        }

        service.finalizeScribeForAppointment(42L, 7L)
        service.finalizeScribeForAppointment(42L, 7L)

        org.junit.jupiter.api.Assertions.assertEquals(1, saved.size)
        verify(fcm, times(1)).sendDoctorNotification("token", saved.first(), emptyMap())
        verify(visitSummaries, times(2)).enqueueGeneration(42L, null, false)
    }

    private fun stubAppointment(appointmentId: Long) {
        val appointment = mock(Appointment::class.java)
        val doctor = mock(DoctorProfile::class.java)
        val patient = mock(PatientProfile::class.java)
        `when`(appointment.doctor).thenReturn(doctor)
        `when`(appointment.patient).thenReturn(patient)
        `when`(appointment.status).thenReturn(Appointment.Status.COMPLETED)
        `when`(doctor.id).thenReturn(7L)
        `when`(patient.id).thenReturn(9L)
        `when`(appointments.findByIdWithDoctorAndPatient(appointmentId)).thenReturn(Optional.of(appointment))
    }

    private fun stubSuccessfulPipeline(appointmentId: Long, audio: Path) {
        stubAppointment(appointmentId)
        val created = AtomicBoolean(false)
        `when`(drafts.hasActiveScribeDraft(appointmentId, ScribePipelineService.SCRIBE_LABEL)).thenAnswer {
            created.get()
        }
        `when`(drafts.hasGeneratedScribeDraft(appointmentId, ScribePipelineService.SCRIBE_LABEL)).thenReturn(false)
        `when`(
            transcription.transcribe(audio, "uz", TranscriptionPurpose.SCRIBE_PIPELINE)
        ).thenReturn(
            TranscriptionService.TranscriptionResult("Patient reports headache", "uz")
        )
        `when`(summaries.summarize(anyValue())).thenReturn(
            MedicalSummaryService.ScribeSummaryResult("headache", "tension", "rest", "body")
        )
        `when`(summaries.formatAsScribeNote(anyValue(), anyString())).thenReturn("formatted")
        `when`(
            drafts.createDraft(anyLong(), anyValue(), anyValue(), anyString(), anyString())
        ).thenAnswer {
            created.set(true)
            mock(AiDraftNote::class.java)
        }
        `when`(diagnoses.suggestFromText(anyString())).thenReturn(emptyList())
        `when`(drafts.confirmGeneratedScribeDrafts(anyLong(), anyLong(), anyString())).thenReturn(emptyList())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyValue(): T {
        Mockito.any<T>()
        return null as T
    }
}
