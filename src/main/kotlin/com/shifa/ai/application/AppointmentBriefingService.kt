package com.shifa.ai.application

import com.shifa.ai.infrastructure.OpenAiResponsesService
import com.shifa.documents.application.PatientDocumentService
import com.shifa.documents.application.PatientDocumentStorageService
import com.shifa.documents.application.PdfTextExtractionService

import com.shifa.appointments.domain.Appointment
import com.shifa.ai.domain.AppointmentBriefing
import com.shifa.notifications.domain.Notification
import com.shifa.documents.domain.PatientDocument
import com.shifa.ai.infrastructure.AppointmentBriefingRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

data class AppointmentBriefingDto(
    val appointmentId: Long,
    val status: String,
    val briefingText: String?,
    val documentCount: Int,
    val generatedAt: String?,
    val error: String?,
)

/**
 * Creates and asynchronously generates appointment-scoped AI briefings from
 * pre-appointment attached documents (+ appointment reason).
 */
@Service
class AppointmentBriefingService(
    private val briefingRepo: AppointmentBriefingRepository,
    private val appointmentRepo: com.shifa.appointments.application.Appointments,
    private val patientDocumentService: PatientDocumentService,
    private val documentStorage: PatientDocumentStorageService,
    private val pdfExtraction: PdfTextExtractionService,
    private val openAi: OpenAiResponsesService,
    private val notificationService: com.shifa.notifications.application.NotificationService,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val transactionTemplate = TransactionTemplate(transactionManager)

    /** Soft caps to keep PDF extraction + OpenAI work bounded per job. */
    companion object {
        const val MAX_ATTACHED_DOCS = 5
        const val MAX_CHARS_PER_DOC = 8_000
        /** Keep under typical OpenAI payload size; less PDFBox stripper work. */
        const val MAX_TOTAL_CHARS = 12_000
        /** Skip PDFBox work on very large files (bytes). */
        const val MAX_PDF_BYTES = 4 * 1024 * 1024
    }

    /** Per-JVM dedupe so book+retry / queue bursts never PDFBox the same appointment twice. */
    private val inFlightAppointmentIds = ConcurrentHashMap.newKeySet<Long>()

    private data class ClaimedBriefing(
        val appointment: Appointment,
        val attached: List<PatientDocument>,
        val rowId: Long,
    )

    @Transactional
    fun enqueueIfNeeded(appointmentId: Long): AppointmentBriefing {
        val appointment = appointmentRepo.findById(appointmentId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found") }
        val attached = patientDocumentService.listForAppointment(appointmentId)
        val existing = briefingRepo.findByAppointmentId(appointmentId).orElse(null)
        if (attached.isEmpty()) {
            val row = existing ?: AppointmentBriefing(
                appointment = appointment,
                status = AppointmentBriefing.Status.SKIPPED,
            )
            row.status = AppointmentBriefing.Status.SKIPPED
            row.documentCount = 0
            row.briefingText = null
            row.error = null
            row.updatedAt = Instant.now()
            return briefingRepo.save(row)
        }
        // Do not reset a job that is already generating or ready.
        if (existing?.status == AppointmentBriefing.Status.GENERATING) {
            return existing
        }
        if (existing?.status == AppointmentBriefing.Status.READY &&
            !existing.briefingText.isNullOrBlank()
        ) {
            return existing
        }
        val row = existing ?: AppointmentBriefing(
            appointment = appointment,
            status = AppointmentBriefing.Status.PENDING,
        )
        row.status = AppointmentBriefing.Status.PENDING
        row.documentCount = attached.size
        row.briefingText = null
        row.error = null
        row.generatedAt = null
        row.updatedAt = Instant.now()
        return briefingRepo.save(row)
    }

    @Async("briefingTaskExecutor")
    fun generateAsync(appointmentId: Long) {
        if (!inFlightAppointmentIds.add(appointmentId)) {
            log.info("Skipping duplicate appointment briefing job appointmentId={}", appointmentId)
            return
        }
        try {
            val existing = briefingRepo.findByAppointmentId(appointmentId).orElse(null)
            if (existing?.status == AppointmentBriefing.Status.READY &&
                !existing.briefingText.isNullOrBlank()
            ) {
                return
            }
            generate(appointmentId)
        } catch (ex: Exception) {
            log.warn("Appointment briefing failed for appointmentId={}: {}", appointmentId, ex.message)
            markFailed(appointmentId, ex.message ?: "Briefing generation failed")
        } finally {
            inFlightAppointmentIds.remove(appointmentId)
        }
    }

    /**
     * PDF/OpenAI work runs outside a long DB transaction so pool threads are not
     * held during CPU-bound extraction. Claim/finalize use short transactions.
     */
    fun generate(appointmentId: Long): AppointmentBriefing {
        val claimed = claimForGeneration(appointmentId)
            ?: return briefingRepo.findByAppointmentId(appointmentId).orElseThrow {
                IllegalArgumentException("Appointment briefing not found: $appointmentId")
            }

        val excerpts = mutableListOf<String>()
        var totalChars = 0
        for (doc in claimed.attached.take(MAX_ATTACHED_DOCS)) {
            if (totalChars >= MAX_TOTAL_CHARS) break
            val resource = documentStorage.getFileResource(doc.filePath) ?: continue
            val contentLength = runCatching { resource.contentLength() }.getOrDefault(-1L)
            if (contentLength > MAX_PDF_BYTES) {
                log.info(
                    "Skipping large PDF for briefing appointmentId={} path={} bytes={}",
                    appointmentId,
                    doc.filePath,
                    contentLength,
                )
                continue
            }
            val raw = pdfExtraction.extractText(resource)
            if (raw.isBlank()) continue
            val room = (MAX_TOTAL_CHARS - totalChars).coerceAtLeast(0)
            if (room <= 0) break
            val text = raw.take(minOf(MAX_CHARS_PER_DOC, room))
            excerpts.add("Document: ${doc.title} (${doc.date.format(dateFormatter)})\n$text")
            totalChars += text.length
        }

        if (excerpts.isEmpty()) {
            return finalizeFailed(
                claimed.rowId,
                claimed.attached.size,
                "Attached documents had no extractable text (PDF text preferred).",
            )
        }

        val context = buildString {
            appendLine("Appointment reason: ${claimed.appointment.reason ?: "(none)"}")
            appendLine("Appointment start: ${claimed.appointment.startAt}")
            appendLine("Location: ${claimed.appointment.location}")
            appendLine()
            appendLine("Attached pre-appointment documents:")
            excerpts.forEach { appendLine(it); appendLine() }
        }.take(MAX_TOTAL_CHARS)

        val language = claimed.appointment.patient?.language?.takeIf { it.isNotBlank() } ?: "en"
        val briefingText = openAi.completeBriefing(context, language)
        return finalizeReady(claimed.rowId, claimed.appointment, claimed.attached.size, briefingText)
    }

    private fun claimForGeneration(appointmentId: Long): ClaimedBriefing? =
        transactionTemplate.execute { _ ->
            val appointment = appointmentRepo.findById(appointmentId)
                .orElseThrow { IllegalArgumentException("Appointment not found: $appointmentId") }
            val attached = patientDocumentService.listForAppointment(appointmentId)
            var row = briefingRepo.findByAppointmentId(appointmentId).orElseGet {
                AppointmentBriefing(appointment = appointment, status = AppointmentBriefing.Status.PENDING)
            }
            if (attached.isEmpty()) {
                row.status = AppointmentBriefing.Status.SKIPPED
                row.documentCount = 0
                row.briefingText = null
                row.error = null
                row.updatedAt = Instant.now()
                briefingRepo.save(row)
                return@execute null
            }
            if (row.status == AppointmentBriefing.Status.READY && !row.briefingText.isNullOrBlank()) {
                return@execute null
            }
            if (row.status == AppointmentBriefing.Status.GENERATING) {
                val ageMinutes = java.time.Duration.between(row.updatedAt, Instant.now()).toMinutes()
                // Allow reclaim only if a prior worker likely crashed mid-job.
                if (ageMinutes < 10) {
                    return@execute null
                }
                log.warn(
                    "Reclaiming stale GENERATING briefing rowId={} appointmentId={} ageMinutes={}",
                    row.id,
                    appointmentId,
                    ageMinutes,
                )
            }
            row.status = AppointmentBriefing.Status.GENERATING
            row.documentCount = attached.size
            row.error = null
            row.updatedAt = Instant.now()
            row = briefingRepo.save(row)
            ClaimedBriefing(appointment, attached, row.id)
        }

    private fun finalizeReady(
        rowId: Long,
        appointment: Appointment,
        documentCount: Int,
        briefingText: String,
    ): AppointmentBriefing =
        transactionTemplate.execute { _ ->
            val row = briefingRepo.findById(rowId).orElseThrow {
                IllegalArgumentException("Briefing row not found: $rowId")
            }
            row.status = AppointmentBriefing.Status.READY
            row.briefingText = briefingText
            row.documentCount = documentCount
            row.generatedAt = Instant.now()
            row.error = null
            row.updatedAt = Instant.now()
            val saved = briefingRepo.save(row)
            notifyDoctorBriefingReady(appointment, documentCount)
            saved
        }!!

    private fun finalizeFailed(rowId: Long, documentCount: Int, message: String): AppointmentBriefing =
        transactionTemplate.execute { _ ->
            val row = briefingRepo.findById(rowId).orElseThrow {
                IllegalArgumentException("Briefing row not found: $rowId")
            }
            row.status = AppointmentBriefing.Status.FAILED
            row.documentCount = documentCount
            row.error = message.take(2000)
            row.updatedAt = Instant.now()
            briefingRepo.save(row)
        }!!

    @Transactional(readOnly = true)
    fun getForAppointment(appointmentId: Long): AppointmentBriefingDto {
        appointmentRepo.findById(appointmentId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found") }
        val row = briefingRepo.findByAppointmentId(appointmentId).orElse(null)
            ?: return AppointmentBriefingDto(
                appointmentId = appointmentId,
                status = "NONE",
                briefingText = null,
                documentCount = patientDocumentService.findByAppointmentId(appointmentId).size,
                generatedAt = null,
                error = null,
            )
        return toDto(row)
    }

    /**
     * Batch summary for calendar/home cards — 2 queries total instead of N+1 per appointment.
     * Returns map appointmentId -> (status?, attachmentCount).
     */
    @Transactional(readOnly = true)
    fun summariesForAppointments(appointmentIds: Collection<Long>): Map<Long, Pair<String?, Int>> {
        if (appointmentIds.isEmpty()) return emptyMap()
        val briefings = briefingRepo.findByAppointmentIdIn(appointmentIds)
            .associateBy { it.appointment.id }
        val counts = patientDocumentService.countByAppointmentIds(appointmentIds).associate { row ->
            val id = (row[0] as Number).toLong()
            val count = (row[1] as Number).toInt()
            id to count
        }
        return appointmentIds.associateWith { id ->
            val row = briefings[id]
            val status = row?.status?.name
            val count = row?.documentCount?.takeIf { it > 0 } ?: (counts[id] ?: 0)
            status to count
        }
    }

    @Transactional
    fun retryForAppointment(appointmentId: Long): AppointmentBriefingDto {
        appointmentRepo.findById(appointmentId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found") }
        val existing = briefingRepo.findByAppointmentId(appointmentId).orElse(null)
        if (existing?.status == AppointmentBriefing.Status.GENERATING ||
            inFlightAppointmentIds.contains(appointmentId)
        ) {
            return toDto(
                existing ?: AppointmentBriefing(
                    appointment = appointmentRepo.findById(appointmentId).get(),
                    status = AppointmentBriefing.Status.PENDING,
                )
            )
        }
        val row = enqueueIfNeeded(appointmentId)
        if (row.status == AppointmentBriefing.Status.SKIPPED) {
            return toDto(row)
        }
        generateAsync(appointmentId)
        return toDto(row)
    }

    @Transactional(readOnly = true)
    fun getForDoctor(appointmentId: Long, doctorId: Long): AppointmentBriefingDto {
        val appointment = appointmentRepo.findById(appointmentId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found") }
        if (appointment.doctor.id != doctorId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not your appointment")
        }
        return getForAppointment(appointmentId)
    }

    @Transactional
    fun retry(appointmentId: Long, doctorId: Long): AppointmentBriefingDto {
        val appointment = appointmentRepo.findById(appointmentId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found") }
        if (appointment.doctor.id != doctorId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not your appointment")
        }
        return retryForAppointment(appointmentId)
    }

    @Transactional(readOnly = true)
    fun summaryStatus(appointmentId: Long): String? =
        briefingRepo.findByAppointmentId(appointmentId).orElse(null)?.status?.name

    fun toDto(row: AppointmentBriefing): AppointmentBriefingDto =
        AppointmentBriefingDto(
            appointmentId = row.appointment.id,
            status = row.status.name,
            briefingText = row.briefingText,
            documentCount = row.documentCount,
            generatedAt = row.generatedAt?.toString(),
            error = row.error,
        )

    private fun markFailed(appointmentId: Long, message: String) {
        transactionTemplate.executeWithoutResult { _ ->
            val row = briefingRepo.findByAppointmentId(appointmentId).orElse(null) ?: return@executeWithoutResult
            row.status = AppointmentBriefing.Status.FAILED
            row.error = message.take(2000)
            row.updatedAt = Instant.now()
            briefingRepo.save(row)
        }
    }

    private fun notifyDoctorBriefingReady(appointment: Appointment, documentCount: Int) {
        val doctor = appointment.doctor
        val patientName = appointment.patient?.fullName ?: "Patient"
        val notif = Notification(
            patient = null,
            doctor = doctor,
            title = "Appointment briefing ready",
            message = "AI briefing ready for $patientName ($documentCount attached document(s)).",
            type = Notification.Type.APPOINTMENT_BRIEFING_READY,
            appointmentId = appointment.id,
        )
        val saved = notificationService.save(notif)
        doctor.fcmToken?.let { notificationService.sendDoctorNotification(it, saved) }
    }
}
