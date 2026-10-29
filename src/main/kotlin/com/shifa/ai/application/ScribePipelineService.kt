package com.shifa.ai.application

import com.shifa.ai.domain.OutputLanguage
import com.shifa.ai.domain.TranscriptPromptLeakStripper
import com.shifa.appointments.application.AppointmentDocumentationService
import com.shifa.appointments.application.VisitFactService
import com.shifa.notifications.domain.Notification
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service


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
    private val appointmentRepository: com.shifa.appointments.application.Appointments,
    private val notificationService: com.shifa.notifications.application.NotificationService,
    private val doctorProfileRepository: com.shifa.doctors.application.DoctorProfiles,
    private val appointmentDocumentationService: AppointmentDocumentationService,
    private val visitSummaryService: PatientVisitAiSummaryService,
    private val visitFactService: VisitFactService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** One in-flight scribe job per appointment so webhook + complete cannot both transcribe. */
    private val inFlightAppointments = ConcurrentHashMap.newKeySet<Long>()

    /** Daily recording ids that already produced a scribe (or were claimed for one). */
    private val processedRecordingIds = ConcurrentHashMap.newKeySet<String>()

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

    fun isScribeInFlight(appointmentId: Long): Boolean = inFlightAppointments.contains(appointmentId)

    fun hasStartedOrFinishedScribe(appointmentId: Long): Boolean =
        isScribeInFlight(appointmentId) || aiDraftNoteService.hasActiveScribeDraft(appointmentId, SCRIBE_LABEL)

    @Async("scribeTaskExecutor")
    fun processVideoRecording(roomName: String, recordingId: String) {
        log.info("Scribe pipeline started: roomName={}, recordingId={}", roomName, recordingId)
        var tempPath: Path? = null
        var claimedAppointmentId: Long? = null
        try {
            val appointmentId = parseAppointmentIdFromRoomName(roomName)
                ?: run {
                    log.warn("Could not parse appointmentId from roomName: {}", roomName)
                    return
                }

            if (!processedRecordingIds.add(recordingId)) {
                log.info("Recording {} already claimed for scribe, skipping", recordingId)
                return
            }

            val appointment = appointmentRepository.findByIdWithDoctorAndPatient(appointmentId).orElse(null)
            if (appointment == null) {
                log.warn("Appointment not found: {}", appointmentId)
                processedRecordingIds.remove(recordingId)
                return
            }
            if (appointment.status == com.shifa.appointments.domain.Appointment.Status.CANCELLED) {
                log.info("Skipping cancelled appointment: {}", appointmentId)
                processedRecordingIds.remove(recordingId)
                return
            }

            val doctorId = appointment.doctor.id
            val patientId = appointment.patient.id

            if (!tryClaimScribeRun(appointmentId, doctorId)) {
                if (!aiDraftNoteService.hasActiveScribeDraft(appointmentId, SCRIBE_LABEL)) {
                    processedRecordingIds.remove(recordingId)
                }
                return
            }
            claimedAppointmentId = appointmentId

            tempPath = scribeRecordingService.downloadDailyRecording(recordingId)
            val audioPath = scribeRecordingService.ensureAudioFormat(tempPath)

            runPipeline(doctorId, patientId, appointmentId, audioPath)
            log.info("Scribe pipeline completed: appointmentId={}", appointmentId)
        } catch (e: Exception) {
            log.error("Scribe pipeline failed: roomName={}, recordingId={}", roomName, recordingId, e)
            processedRecordingIds.remove(recordingId)
        } finally {
            claimedAppointmentId?.let { inFlightAppointments.remove(it) }
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
        val claimed = tryClaimScribeRun(appointmentId, doctorId)
        try {
            if (!claimed) {
                return
            }
            runPipeline(doctorId, patientId, appointmentId, localFilePath, languageHint)
            log.info("Scribe pipeline completed: appointmentId={}", appointmentId)
        } catch (e: Exception) {
            log.error("Scribe pipeline failed: appointmentId={}", appointmentId, e)
        } finally {
            if (claimed) {
                inFlightAppointments.remove(appointmentId)
            }
            scribeRecordingService.cleanupTempFile(localFilePath)
        }
    }

    /**
     * Claim exclusive scribe work for this appointment. If a draft already exists,
     * only confirm still-pending GENERATED drafts — never start another transcription.
     */
    private fun tryClaimScribeRun(appointmentId: Long, doctorId: Long): Boolean {
        if (aiDraftNoteService.hasActiveScribeDraft(appointmentId, SCRIBE_LABEL)) {
            log.info("Scribe already exists for appointment {}, skipping new preparation", appointmentId)
            finalizePendingScribeIfNeeded(appointmentId, doctorId)
            return false
        }
        if (!inFlightAppointments.add(appointmentId)) {
            log.info("Scribe already in flight for appointment {}, skipping", appointmentId)
            return false
        }
        if (aiDraftNoteService.hasActiveScribeDraft(appointmentId, SCRIBE_LABEL)) {
            log.info("Scribe appeared before claim for appointment {}, skipping", appointmentId)
            inFlightAppointments.remove(appointmentId)
            finalizePendingScribeIfNeeded(appointmentId, doctorId)
            return false
        }
        return true
    }

    private fun finalizePendingScribeIfNeeded(appointmentId: Long, doctorId: Long) {
        if (!aiDraftNoteService.hasGeneratedScribeDraft(appointmentId, SCRIBE_LABEL)) {
            return
        }
        finalizeScribeForAppointment(appointmentId, doctorId)
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
        val hintEffective = languageHint ?: "uz"
        val transcript = transcriptionService.transcribe(
            audioPath,
            hintEffective,
            TranscriptionPurpose.SCRIBE_PIPELINE
        )
        val spokenTranscript = TranscriptPromptLeakStripper.strip(transcript.transcript)
        if (spokenTranscript.isBlank() || spokenTranscript == "(No speech detected)") {
            log.warn("Empty transcript, skipping draft creation")
            return
        }

        if (aiDraftNoteService.hasActiveScribeDraft(appointmentId, SCRIBE_LABEL)) {
            log.info("Scribe already exists after transcription for appointment {}, skipping duplicate draft", appointmentId)
            finalizePendingScribeIfNeeded(appointmentId, doctorId)
            return
        }

        val outputLanguage = transcriptLanguageToOutput(transcript.language)
        log.info("Scribe pipeline: transcript language={}, output language={}", transcript.language, outputLanguage)

        val summary = medicalSummaryService.summarize(
            MedicalSummaryService.ScribeSummaryRequest(
                transcript = spokenTranscript,
                language = outputLanguage
            )
        )

        if (aiDraftNoteService.hasActiveScribeDraft(appointmentId, SCRIBE_LABEL)) {
            log.info("Scribe already exists after summarization for appointment {}, skipping duplicate draft", appointmentId)
            finalizePendingScribeIfNeeded(appointmentId, doctorId)
            return
        }

        val formattedText = medicalSummaryService.formatAsScribeNote(summary, spokenTranscript)
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

        // Visit fact cards (meds / diagnoses / therapy) stay DRAFT until the doctor documents everything.
        try {
            visitFactService.extractAndStoreDrafts(
                doctorId = doctorId,
                patientId = patientId,
                appointmentId = appointmentId,
                transcript = spokenTranscript,
                language = outputLanguage,
            )
        } catch (e: Exception) {
            log.warn("Visit fact extraction skipped for appointment {}: {}", appointmentId, e.message)
        }

        finalizeScribeForAppointment(appointmentId, doctorId)
    }

    /**
     * Auto-confirm scribe drafts and write them into appointment documentation.
     * Safe to call after the doctor has already ended the visit.
     */
    fun finalizeScribeForAppointment(appointmentId: Long, doctorId: Long) {
        val confirmed = try {
            aiDraftNoteService.confirmGeneratedScribeDrafts(appointmentId, doctorId, SCRIBE_LABEL)
        } catch (e: Exception) {
            log.warn("Could not auto-confirm scribe drafts for appointment {}: {}", appointmentId, e.message)
            emptyList()
        }
        try {
            appointmentDocumentationService.generateForAppointment(appointmentId)
        } catch (e: Exception) {
            log.warn("Could not generate appointment documentation for {}: {}", appointmentId, e.message)
        }
        try {
            visitSummaryService.enqueueGeneration(appointmentId, null, force = false)
        } catch (e: Exception) {
            log.warn("Could not enqueue visit summary for appointment {}: {}", appointmentId, e.message)
        }
        if (confirmed.isNotEmpty()) {
            notifyDoctorScribeReady(doctorId, appointmentId)
        }
    }

    private fun notifyDoctorScribeReady(doctorId: Long, appointmentId: Long) {
        try {
            if (notificationService.alreadyNotifiedDoctor(
                    doctorId,
                    appointmentId,
                    Notification.Type.AI_SCRIBE_READY,
                )
            ) {
                log.info("AI Scribe ready notification already sent to doctor {} for appointment {}", doctorId, appointmentId)
                return
            }
            val doctor = doctorProfileRepository.findById(doctorId).orElse(null) ?: return
            val token = doctor.fcmToken ?: return
            val notif = Notification(
                patient = null,
                doctor = doctor,
                title = "AI Scribe",
                message = "Consultation summary is ready and saved to appointment documentation.",
                type = Notification.Type.AI_SCRIBE_READY,
                appointmentId = appointmentId
            )
            val saved = notificationService.save(notif)
            notificationService.sendDoctorNotification(token, saved)
            log.info("Sent AI Scribe ready notification to doctor {} for appointment {}", doctorId, appointmentId)
        } catch (e: Exception) {
            log.warn("Could not send scribe-ready notification: {}", e.message)
        }
    }
}
