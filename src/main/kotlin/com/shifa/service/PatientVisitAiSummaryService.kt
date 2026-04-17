package com.shifa.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.shifa.config.OpenAiProperties
import com.shifa.domain.Notification
import com.shifa.domain.PatientVisitAiSummary
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.ConsultationNoteRepository
import com.shifa.repo.NotificationRepository
import com.shifa.repo.PatientVisitAiSummaryRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.Executor

data class PatientVisitSummaryResult(
    val status: String,
    val language: String,
    val version: Int,
    val generatedAt: String?,
    val content: Map<String, Any>?
)

data class PatientVisitAskResult(
    val answer: String,
    val citations: List<String>
)

@Service
class PatientVisitAiSummaryService(
    private val appointmentRepo: AppointmentRepository,
    private val consultationNoteRepo: ConsultationNoteRepository,
    private val summaryRepo: PatientVisitAiSummaryRepository,
    private val notificationRepo: NotificationRepository,
    private val fcmService: FcmService,
    private val openAi: OpenAiResponsesService,
    private val openAiProps: OpenAiProperties,
    @Qualifier("scribeTaskExecutor")
    private val jobExecutor: Executor
) {
    private val mapper = jacksonObjectMapper()

    @Transactional
    fun enqueueGeneration(
        appointmentId: Long,
        language: String?,
        force: Boolean = false
    ) {
        val appointment = appointmentRepo.findByIdWithDoctorAndPatient(appointmentId)
            .orElseThrow { IllegalArgumentException("Appointment not found: $appointmentId") }
        val patient = appointment.patient
        val lang = (language ?: patient.language ?: "en").lowercase().take(8)

        val existing = summaryRepo.findByAppointmentIdAndLanguage(appointmentId, lang)
        val record = existing ?: PatientVisitAiSummary(
            appointmentId = appointment.id,
            patientId = patient.id ?: throw IllegalStateException("Patient profile id missing"),
            language = lang,
            status = PatientVisitAiSummary.Status.QUEUED,
            modelVersion = openAiProps.model
        )
        if (force || record.status != PatientVisitAiSummary.Status.READY) {
            record.status = PatientVisitAiSummary.Status.QUEUED
            record.updatedAt = Instant.now()
            summaryRepo.save(record)
        }

        jobExecutor.execute {
            try {
                generateForAppointment(appointmentId, lang, force)
            } catch (_: Exception) {
                // Swallow async errors; status is persisted as FAILED by generateForAppointment.
            }
        }
    }

    @Transactional
    fun generateForAppointment(
        appointmentId: Long,
        language: String?,
        force: Boolean = false
    ): PatientVisitAiSummary {
        val appointment = appointmentRepo.findByIdWithDoctorAndPatient(appointmentId)
            .orElseThrow { IllegalArgumentException("Appointment not found: $appointmentId") }
        val patient = appointment.patient
        val lang = (language ?: patient.language ?: "en").lowercase().take(8)

        val note = consultationNoteRepo.findFirstByAppointmentIdOrderByCreatedAtDesc(appointmentId)
            ?: throw IllegalStateException("No finalized consultation note found for appointment")

        val sourceText = buildString {
            appendLine("Appointment context:")
            appendLine("reason=${appointment.reason ?: ""}")
            appendLine("location=${appointment.location}")
            appendLine("status=${appointment.status.name}")
            appendLine()
            appendLine("Doctor-approved consultation note:")
            appendLine("subjective=${note.subjective ?: ""}")
            appendLine("assessment=${note.assessment ?: ""}")
            appendLine("plan=${note.plan ?: ""}")
            appendLine("body=${note.body ?: ""}")
        }
        val newHash = sha256(sourceText)
        val existing = summaryRepo.findByAppointmentIdAndLanguage(appointmentId, lang)

        if (!force && existing != null && existing.status == PatientVisitAiSummary.Status.READY && existing.sourceHash == newHash) {
            return existing
        }

        val record = existing ?: PatientVisitAiSummary(
            appointmentId = appointment.id,
            patientId = patient.id ?: throw IllegalStateException("Patient profile id missing"),
            language = lang,
            status = PatientVisitAiSummary.Status.QUEUED,
            modelVersion = openAiProps.model
        )
        record.status = PatientVisitAiSummary.Status.QUEUED
        record.sourceHash = newHash
        record.updatedAt = Instant.now()
        summaryRepo.save(record)

        return try {
            val json = openAi.generatePatientVisitSummary(sourceText, lang)
            mapper.readTree(json) // validate JSON
            record.status = PatientVisitAiSummary.Status.READY
            record.contentJson = json
            record.modelVersion = openAiProps.model
            record.generatedAt = Instant.now()
            record.updatedAt = Instant.now()
            val saved = summaryRepo.save(record)
            pushSummaryReady(appointmentId, saved)
            saved
        } catch (e: Exception) {
            record.status = PatientVisitAiSummary.Status.FAILED
            record.updatedAt = Instant.now()
            summaryRepo.save(record)
            throw e
        }
    }

    @Transactional(readOnly = true)
    fun getForAppointment(appointmentId: Long, language: String?): PatientVisitSummaryResult {
        val appointment = appointmentRepo.findByIdWithDoctorAndPatient(appointmentId)
            .orElseThrow { IllegalArgumentException("Appointment not found: $appointmentId") }
        val lang = (language ?: appointment.patient.language ?: "en").lowercase().take(8)
        val summary = summaryRepo.findByAppointmentIdAndLanguage(appointmentId, lang)
            ?: return PatientVisitSummaryResult("not_ready", lang, 1, null, null)
        val content = summary.contentJson?.let { mapper.readValue(it, Map::class.java) as Map<String, Any> }
        return PatientVisitSummaryResult(
            status = summary.status.name.lowercase(),
            language = summary.language,
            version = 1,
            generatedAt = summary.generatedAt?.toString(),
            content = content
        )
    }

    @Transactional(readOnly = true)
    fun askForAppointment(appointmentId: Long, language: String?, question: String): PatientVisitAskResult {
        val summary = getForAppointment(appointmentId, language)
        if (summary.status != "ready" || summary.content == null) {
            throw IllegalStateException("Visit summary is not ready")
        }
        val summaryJson = mapper.writeValueAsString(summary.content)
        return openAi.answerPatientVisitQuestion(summaryJson, question.trim(), summary.language)
    }

    private fun pushSummaryReady(appointmentId: Long, summary: PatientVisitAiSummary) {
        val appointment = appointmentRepo.findByIdWithDoctorAndPatient(appointmentId).orElse(null) ?: return
        val patient = appointment.patient
        val notif = Notification(
            patient = patient,
            title = "Visit summary is ready",
            message = "Your after-visit summary is now available.",
            type = Notification.Type.AI_VISIT_SUMMARY_READY,
            appointmentId = appointmentId
        )
        val saved = notificationRepo.save(notif)
        fcmService.sendPatientNotification(
            fcmToken = patient.fcmToken,
            notification = saved,
            extraData = mapOf(
                "entityId" to appointmentId.toString(),
                "notificationId" to saved.id.toString(),
                "route" to "/bookings/$appointmentId/visit-summary",
                "type" to "AI_VISIT_SUMMARY_READY"
            )
        )
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}

