package com.shifa.service

import com.shifa.domain.Appointment
import com.shifa.domain.PatientDocument
import com.shifa.domain.PatientDocumentCategory
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.ConsultationNoteRepository
import com.shifa.repo.DoctorSettingsRepository
import com.shifa.repo.PatientDocumentRepository
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Creates or replaces the appointment documentation PDF (AI scribe first, doctor notes after).
 */
@Service
class AppointmentDocumentationService(
    private val appointmentRepository: AppointmentRepository,
    private val consultationNoteRepository: ConsultationNoteRepository,
    private val patientDocumentRepository: PatientDocumentRepository,
    private val storage: PatientDocumentStorageService,
    private val doctorSettingsRepository: DoctorSettingsRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class ScribeStatus(
        val status: String,
        val hasDocumentation: Boolean,
        val hasScribeNote: Boolean,
    )

    fun statusFor(appointmentId: Long): ScribeStatus {
        val notes = consultationNoteRepository.findByAppointmentIdOrderByCreatedAtAsc(appointmentId)
        val hasScribeNote = notes.any { it.source == AppointmentDocumentationComposer.SOURCE_AI }
        val hasDocumentation = findExistingNote(appointmentId) != null
        val status = when {
            hasScribeNote -> "ready"
            else -> "none"
        }
        return ScribeStatus(
            status = status,
            hasDocumentation = hasDocumentation,
            hasScribeNote = hasScribeNote,
        )
    }

    @Transactional
    fun generateForAppointment(appointmentId: Long): PatientDocument? {
        val appointment = appointmentRepository.findByIdWithDoctorAndPatient(appointmentId).orElse(null)
            ?: run {
                log.warn("Cannot generate documentation: appointment {} not found", appointmentId)
                return null
            }
        val notes = consultationNoteRepository.findByAppointmentIdOrderByCreatedAtAsc(appointmentId)
        if (notes.isEmpty()) {
            log.info("Skipping documentation PDF: no consultation notes for appointment {}", appointmentId)
            return null
        }
        val language = doctorSettingsRepository.findByDoctorId(appointment.doctor.id)
            .map { it.language }
            .orElse(appointment.patient.language)
        val body = AppointmentDocumentationComposer.compose(notes, language)
        if (body.isBlank()) {
            log.info("Skipping documentation PDF: empty composed notes for appointment {}", appointmentId)
            return null
        }
        val zone = runCatching { ZoneId.of(appointment.doctor.timeZone) }.getOrElse { ZoneId.of("Asia/Tashkent") }
        val completedAt = java.time.ZonedDateTime.now(zone)
        val dateStr = completedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val timeStr = completedAt.format(DateTimeFormatter.ofPattern("HH:mm"))
        val title = "Appointment Documentation - $dateStr $timeStr"
        val doctorName = listOf(appointment.doctor.firstName, appointment.doctor.lastName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifBlank { "Doctor" }
        val pdfBytes = renderPdf(
            title = title,
            patientName = appointment.patient.fullName,
            doctorName = doctorName,
            appointmentType = appointment.location,
            dateStr = dateStr,
            timeStr = timeStr,
            body = body,
        )
        return upsertAppointmentNote(appointment, pdfBytes, title, completedAt.toLocalDate())
    }

    private fun findExistingNote(appointmentId: Long): PatientDocument? {
        return patientDocumentRepository.findByAppointmentId(appointmentId)
            .firstOrNull { it.category == PatientDocumentCategory.APPOINTMENT_NOTE.code }
    }

    private fun upsertAppointmentNote(
        appointment: Appointment,
        pdfBytes: ByteArray,
        title: String,
        date: LocalDate,
    ): PatientDocument {
        val patientId = requireNotNull(appointment.patient.id) { "Appointment has no patient" }
        val existing = findExistingNote(appointment.id)
        val saved = storage.saveBytes(patientId, pdfBytes, "appointment-documentation", "pdf")
        if (existing != null) {
            val oldPath = existing.filePath
            existing.filePath = saved.filePathRelative
            existing.title = title
            existing.date = date
            val updated = patientDocumentRepository.save(existing)
            if (oldPath.isNotBlank() && oldPath != saved.filePathRelative) {
                storage.deleteFile(oldPath)
            }
            log.info("Updated appointment documentation document {} for appointment {}", updated.id, appointment.id)
            return updated
        }
        val entity = PatientDocument(
            title = title,
            date = date,
            filePath = saved.filePathRelative,
            patient = appointment.patient,
            uploadedByDoctor = appointment.doctor,
            category = PatientDocumentCategory.APPOINTMENT_NOTE.code,
            isSharedWithTeam = false,
            appointment = appointment,
        )
        val persisted = patientDocumentRepository.save(entity)
        log.info("Created appointment documentation document {} for appointment {}", persisted.id, appointment.id)
        return persisted
    }

    private fun renderPdf(
        title: String,
        patientName: String?,
        doctorName: String,
        appointmentType: String,
        dateStr: String,
        timeStr: String,
        body: String,
    ): ByteArray {
        val fontBytes = javaClass.getResourceAsStream("/fonts/DejaVuSans.ttf")?.use { it.readBytes() }
            ?: throw IllegalStateException("Missing classpath font /fonts/DejaVuSans.ttf")
        val maxCharsPerLine = 92
        val leading = 14f
        val marginTop = 56f
        val marginBottom = 50f
        val startX = 50f
        val header = listOf(
            title,
            "Patient: ${patientName?.trim().orEmpty().ifBlank { "-" }}",
            "Doctor: $doctorName",
            "Type: $appointmentType",
            "Date: $dateStr  $timeStr",
            "",
        )
        val lines = header + wrapLines(body, maxCharsPerLine)

        PDDocument().use { doc ->
            val font = PDType0Font.load(doc, fontBytes.inputStream())
            var page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            var y = page.mediaBox.height - marginTop
            var contentStream = PDPageContentStream(doc, page)
            contentStream.setFont(font, 11f)
            contentStream.beginText()
            contentStream.newLineAtOffset(startX, y)

            for (line in lines) {
                if (y < marginBottom) {
                    contentStream.endText()
                    contentStream.close()
                    page = PDPage(PDRectangle.A4)
                    doc.addPage(page)
                    y = page.mediaBox.height - marginTop
                    contentStream = PDPageContentStream(doc, page)
                    contentStream.setFont(font, 11f)
                    contentStream.beginText()
                    contentStream.newLineAtOffset(startX, y)
                }
                contentStream.showText(sanitizePdfLine(line))
                contentStream.newLineAtOffset(0f, -leading)
                y -= leading
            }

            contentStream.endText()
            contentStream.close()
            val baos = ByteArrayOutputStream()
            doc.save(baos)
            return baos.toByteArray()
        }
    }

    private fun wrapLines(text: String, maxCharsPerLine: Int): List<String> {
        val out = mutableListOf<String>()
        for (src in text.replace("\r\n", "\n").split('\n')) {
            if (src.isEmpty()) {
                out.add("")
                continue
            }
            var remaining = src
            while (remaining.length > maxCharsPerLine) {
                val window = remaining.take(maxCharsPerLine)
                val breakAt = window.lastIndexOf(' ').takeIf { it >= 40 } ?: maxCharsPerLine
                out.add(remaining.take(breakAt).trimEnd())
                remaining = remaining.drop(breakAt).trimStart()
            }
            if (remaining.isNotEmpty()) out.add(remaining)
        }
        return out
    }

    private fun sanitizePdfLine(line: String): String {
        return line.replace('\t', ' ').replace(Regex("[\\p{Cntrl}&&[^\n]]"), "")
    }
}
