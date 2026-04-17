package com.shifa.service

import com.shifa.ai.OutputLanguage
import com.shifa.domain.Notification
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.NotificationRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.nio.file.Path

/**
 * Orchestrates the AI scribe pipeline: recording -> transcription -> summarization -> ai_draft_notes.
 * Runs asynchronously to avoid blocking webhook/upload handlers.
 */
@Service
class ScribePipelineService(
    private val scribeRecordingService: ScribeRecordingService,
    private val transcriptionService: TranscriptionService,
    private val medicalSummaryService: MedicalSummaryService,
    private val aiDraftNoteService: AiDraftNoteService,
    private val diagnosisSuggestionService: DiagnosisSuggestionService,
    private val appointmentRepository: AppointmentRepository,
    private val notificationRepository: NotificationRepository,
    private val doctorProfileRepository: DoctorProfileRepository,
    private val fcmService: FcmService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val SCRIBE_LABEL = "AI Scribe - Consultation Summary"

        fun parseAppointmentIdFromRoomName(roomName: String): Long? {
            return roomName.removePrefix("appointment-").toLongOrNull()
        }

        /**
         * Map Whisper language code (ISO 639-1) to output language for summary.
         *
         * In our deployment we primarily serve Uzbek-speaking doctors and patients, and Whisper
         * can sometimes label Uzbek speech as neighbouring languages (e.g. \"az\" for
         * Azerbaijani or \"tr\" for Turkish). To make the scribe more robust, we treat those
         * variants as Uzbek for output purposes.
         */
        fun transcriptLanguageToOutput(lang: String?): OutputLanguage = when (lang?.lowercase()) {
            // Explicit Russian / English stay as-is
            "ru", "rus", "russian" -> OutputLanguage.RU
            "en", "eng", "english" -> OutputLanguage.EN

            // Uzbek and closely related codes → Uzbek output
            "uz", "uzb", "uzbek" -> OutputLanguage.UZ
            "az", "aze", "azerbaijani" -> OutputLanguage.UZ
            "tr", "tur", "turkish" -> OutputLanguage.UZ

            // Default: assume Uzbek (clinic default) rather than English
            else -> OutputLanguage.UZ
        }
    }

    @Async("scribeTaskExecutor")
    fun processVideoRecording(roomName: String, recordingId: String) {
        log.info("Scribe pipeline started: roomName={}, recordingId={}", roomName, recordingId)
        var tempPath: Path? = null
        try {
            val appointmentId = parseAppointmentIdFromRoomName(roomName)
                ?: run {
                    log.warn("Could not parse appointmentId from roomName: {}", roomName)
                    return
                }

            val appointment = appointmentRepository.findByIdWithDoctorAndPatient(appointmentId).orElse(null)
            if (appointment == null) {
                log.warn("Appointment not found: {}", appointmentId)
                return
            }
            if (appointment.status == com.shifa.domain.Appointment.Status.CANCELLED) {
                log.info("Skipping cancelled appointment: {}", appointmentId)
                return
            }

            val doctorId = appointment.doctor.id
            val patientId = appointment.patient.id

            tempPath = scribeRecordingService.downloadDailyRecording(recordingId)
            val audioPath = scribeRecordingService.ensureAudioFormat(tempPath)

            runPipeline(doctorId, patientId, appointmentId, audioPath)
            log.info("Scribe pipeline completed: appointmentId={}", appointmentId)
        } catch (e: Exception) {
            log.error("Scribe pipeline failed: roomName={}, recordingId={}", roomName, recordingId, e)
        } finally {
            tempPath?.let { scribeRecordingService.cleanupTempFile(it) }
        }
    }

    @Async("scribeTaskExecutor")
    fun processInPersonRecording(
        appointmentId: Long,
        doctorId: Long,
        patientId: Long?,
        localFilePath: Path,
        languageHint: String? = null
    ) {
        log.info("Scribe pipeline started (in-person): appointmentId={}", appointmentId)
        try {
            runPipeline(doctorId, patientId, appointmentId, localFilePath, languageHint)
            log.info("Scribe pipeline completed: appointmentId={}", appointmentId)
        } catch (e: Exception) {
            log.error("Scribe pipeline failed: appointmentId={}", appointmentId, e)
        } finally {
            scribeRecordingService.cleanupTempFile(localFilePath)
        }
    }

    private fun runPipeline(
        doctorId: Long,
        patientId: Long?,
        appointmentId: Long,
        audioPath: Path,
        languageHint: String? = null
    ) {
        if (patientId == null) {
            log.warn("Scribe pipeline: patientId is null for appointmentId={}, skipping", appointmentId)
            return
        }
        val transcript = transcriptionService.transcribe(audioPath, languageHint)
        if (transcript.transcript.isBlank() || transcript.transcript == "(No speech detected)") {
            log.warn("Empty transcript, skipping draft creation")
            return
        }

        val outputLanguage = transcriptLanguageToOutput(transcript.language)
        log.info("Scribe pipeline: transcript language={}, output language={}", transcript.language, outputLanguage)

        val summary = medicalSummaryService.summarize(
            MedicalSummaryService.ScribeSummaryRequest(
                transcript = transcript.transcript,
                language = outputLanguage
            )
        )
        val formattedText = medicalSummaryService.formatAsScribeNote(summary)
        val draft = aiDraftNoteService.createDraft(
            doctorId = doctorId,
            patientId = patientId,
            consultationId = appointmentId,
            aiResponseText = formattedText,
            aiLabel = SCRIBE_LABEL
        )

        // Optional ICD-10 suggestions. Must never block the pipeline.
        try {
            // Guardrail: never overwrite any persisted diagnosis. These are optional UI hints only.
            val suggestions = diagnosisSuggestionService.suggestFromText(formattedText)
            if (suggestions.isNotEmpty()) {
                aiDraftNoteService.attachIcdSuggestions(draft, suggestions)
            }
        } catch (e: Exception) {
            log.warn("ICD suggestions skipped for draftId={}: {}", draft.id, e.message)
        }

        notifyDoctorScribeReady(doctorId, appointmentId)
    }

    private fun notifyDoctorScribeReady(doctorId: Long, appointmentId: Long) {
        try {
            val doctor = doctorProfileRepository.findById(doctorId).orElse(null) ?: return
            val token = doctor.fcmToken ?: return
            val notif = Notification(
                patient = null,
                doctor = doctor,
                title = "AI Scribe",
                message = "Consultation summary is ready. Open the appointment to view.",
                type = Notification.Type.AI_SCRIBE_READY,
                appointmentId = appointmentId
            )
            val saved = notificationRepository.save(notif)
            fcmService.sendDoctorNotification(token, saved)
            log.info("Sent AI Scribe ready notification to doctor {} for appointment {}", doctorId, appointmentId)
        } catch (e: Exception) {
            log.warn("Could not send scribe-ready notification: {}", e.message)
        }
    }
}
