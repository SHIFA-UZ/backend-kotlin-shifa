package com.shifa.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.domain.AiDraftNote
import com.shifa.domain.Appointment
import com.shifa.domain.PatientProfile
import com.shifa.domain.Role
import com.shifa.domain.User
import com.shifa.repo.*
import com.shifa.security.AdminPrincipal
import com.shifa.service.AuditService
import com.shifa.service.PatientDocumentService
import com.shifa.service.PatientFormService
import com.shifa.util.PhoneNormalizer
import com.shifa.web.dto.PatientDocumentDto
import com.shifa.web.dto.PatientFormDto
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpHeaders
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.util.UUID
import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.transaction.annotation.Transactional as SpringTransactional
import com.shifa.domain.ConsultationNote
import org.slf4j.LoggerFactory

@RestController
@RequestMapping("/api/admin/patients/deleted")
class AdminDeletedPatientsController(
    private val users: UserRepository,
    private val patientProfiles: PatientProfileRepository,
    private val appointments: AppointmentRepository,
    private val patientDocumentService: PatientDocumentService,
    private val patientFormService: PatientFormService,
    private val consultationNoteRepository: ConsultationNoteRepository,
    private val aiDraftNoteRepository: AiDraftNoteRepository,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(AdminDeletedPatientsController::class.java)

    data class DeletedPatientSearchRequest(
        val phone: String? = null,
        val email: String? = null,
        val userId: Long? = null,
    )

    data class DeletedPatientMatchDto(
        val userId: Long,
        val patientProfileId: Long,
        val deletedAt: String?,
        val matchedBy: String,
        val maskedPhone: String?,
        val maskedEmail: String?,
    )

    data class DeletedPatientSearchResponse(
        val matches: List<DeletedPatientMatchDto>,
    )

    @PostMapping("/search")
    fun searchDeletedPatients(
        @AuthenticationPrincipal principal: AdminPrincipal,
        @RequestBody request: DeletedPatientSearchRequest
    ): DeletedPatientSearchResponse {
        val phoneInput = request.phone?.trim().takeIf { !it.isNullOrBlank() }
        val emailInput = request.email?.trim().takeIf { !it.isNullOrBlank() }
        val userIdInput = request.userId

        if (phoneInput == null && emailInput == null && userIdInput == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide phone, email, or userId")
        }

        val matchesByPatientProfileId = linkedMapOf<Long, DeletedPatientMatchDto>()

        fun sha256Hex(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun maskPhone(phone: String): String? {
            val digits = phone.filter { it.isDigit() }
            if (digits.length < 4) return "***"
            return "***${digits.takeLast(2)}"
        }

        fun maskEmail(email: String): String? {
            val trimmed = email.trim()
            val at = trimmed.indexOf('@')
            if (at <= 0 || at >= trimmed.length - 1) return "***"
            val domain = trimmed.substring(at + 1)
            return "***@$domain"
        }

        if (userIdInput != null) {
            val user = users.findById(userIdInput).orElse(null)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
            if (user.role != Role.PATIENT || user.accountStatus != User.AccountStatus.DELETED) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "User is not a deleted patient")
            }
            val profile = patientProfiles.findByUserId(userIdInput).orElse(null)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient profile not found for deleted user")
            matchesByPatientProfileId[requireNotNull(profile.id)] = DeletedPatientMatchDto(
                userId = user.id,
                patientProfileId = requireNotNull(profile.id),
                deletedAt = user.deletedAt?.toString(),
                matchedBy = "USER_ID",
                maskedPhone = null,
                maskedEmail = null,
            )
        }

        if (phoneInput != null && matchesByPatientProfileId.isEmpty()) {
            val normalized = PhoneNormalizer.normalize(phoneInput)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid phone")
            val phoneHash = sha256Hex(normalized)
            val user = users.findByPhoneOriginalHashAndAccountStatus(phoneHash).orElse(null)
                ?: null
            if (user != null && user.role == Role.PATIENT && user.accountStatus == User.AccountStatus.DELETED) {
                val profile = patientProfiles.findByUserId(user.id).orElse(null)
                if (profile?.id != null) {
                    matchesByPatientProfileId[profile.id!!] = DeletedPatientMatchDto(
                        userId = user.id,
                        patientProfileId = profile.id!!,
                        deletedAt = user.deletedAt?.toString(),
                        matchedBy = "PHONE",
                        maskedPhone = maskPhone(phoneInput),
                        maskedEmail = null,
                    )
                }
            }
        }

        if (emailInput != null && matchesByPatientProfileId.isEmpty()) {
            val normalized = emailInput.trim().lowercase()
            val emailHash = sha256Hex(normalized)
            val user = users.findByEmailOriginalHashAndAccountStatus(emailHash).orElse(null)
                ?: null
            if (user != null && user.role == Role.PATIENT && user.accountStatus == User.AccountStatus.DELETED) {
                val profile = patientProfiles.findByUserId(user.id).orElse(null)
                if (profile?.id != null) {
                    matchesByPatientProfileId[profile.id!!] = DeletedPatientMatchDto(
                        userId = user.id,
                        patientProfileId = profile.id!!,
                        deletedAt = user.deletedAt?.toString(),
                        matchedBy = "EMAIL",
                        maskedPhone = null,
                        maskedEmail = maskEmail(emailInput),
                    )
                }
            }
        }

        // Audit without logging the raw phone/email.
        val adminUser = principal.adminProfile.user
        auditService.logAction(
            adminUser = adminUser,
            actionType = "DELETED_PATIENT_SEARCH",
            entityType = "DELETED_PATIENT",
            details = mapOf(
                "matchedBy" to listOf(
                    phoneInput?.let { "PHONE" },
                    emailInput?.let { "EMAIL" },
                    userIdInput?.let { "USER_ID" }
                ).filterNotNull().joinToString(","),
                "count" to matchesByPatientProfileId.size,
            )
        )

        return DeletedPatientSearchResponse(matches = matchesByPatientProfileId.values.toList())
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class DeletedPatientExportDto(
        val userId: Long,
        val patientProfileId: Long,
        val deletedAt: String?,
        val deletionRequestedAt: String?,
        val patientProfile: PatientProfileExportDto,
        val appointments: List<AppointmentExportDto>,
        val documents: List<PatientDocumentDto>,
        val forms: List<PatientFormDto>,
        val consultationNotes: List<ConsultationNoteExportDto>,
        val aiDraftNotes: List<AiDraftNoteExportDto>,
    )

    data class PatientProfileExportDto(
        val id: Long,
        val fullName: String,
        val birthDate: String?,
        val language: String?,
        val timeZone: String?,
        val chronicDisease: String?,
        val address: String?,
        val photoUrl: String?,
    )

    data class AppointmentExportDto(
        val id: Long,
        val doctorId: Long,
        val doctorName: String,
        val startAt: String,
        val endAt: String,
        val location: String,
        val reason: String?,
        val status: String,
        val signatureRequested: Boolean,
        val patientSignedAt: String?,
    )

    data class ConsultationNoteExportDto(
        val id: Long?,
        val doctorId: Long,
        val patientId: Long,
        val appointmentId: Long?,
        val aiDraftNoteId: UUID?,
        val subjective: String?,
        val assessment: String?,
        val plan: String?,
        val body: String?,
        val createdAt: String,
        val source: String,
    )

    data class AiDraftNoteExportDto(
        val id: UUID?,
        val doctorId: Long,
        val patientId: Long?,
        val consultationId: Long?,
        val aiLabel: String,
        val aiResponseText: String,
        val icdSuggestionsJson: String?,
        val createdAt: String,
        val status: String,
        val modelVersion: String,
        val promptVersion: String,
    )

    @GetMapping("/{patientProfileId}/export")
    @SpringTransactional(readOnly = true)
    fun exportDeletedPatient(
        @AuthenticationPrincipal principal: AdminPrincipal,
        @PathVariable patientProfileId: Long,
        @RequestParam(required = false, defaultValue = "false") includeChat: Boolean
    ): DeletedPatientExportDto {
        if (includeChat) {
            // Chat messages are hard-deleted by PatientAccountDeletionService.
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "includeChat=true is not available (chat hard-deleted)"
            )
        }

        val export = buildDeletedPatientExportDto(patientProfileId)

        val adminUser = principal.adminProfile.user
        auditService.logAction(
            adminUser = adminUser,
            actionType = "DELETED_PATIENT_EXPORT",
            entityType = "DELETED_PATIENT",
            entityId = patientProfileId,
            details = mapOf("includeChat" to includeChat)
        )

        return export
    }

    @GetMapping("/{patientProfileId}/export/pdf")
    @SpringTransactional(readOnly = true)
    fun exportDeletedPatientPdf(
        @AuthenticationPrincipal principal: AdminPrincipal,
        @PathVariable patientProfileId: Long,
    ): ResponseEntity<ByteArray> {
        // NOTE: Chat export is intentionally not supported because chat data is hard-deleted.
        val export = buildDeletedPatientExportDto(patientProfileId)

        val adminUser = principal.adminProfile.user
        auditService.logAction(
            adminUser = adminUser,
            actionType = "DELETED_PATIENT_EXPORT_PDF",
            entityType = "DELETED_PATIENT",
            entityId = patientProfileId,
            details = mapOf("includeChat" to false)
        )

        val prettyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(export)
        val pdfBytes = renderTextPdf(
            title = "Deleted Patient Export (Legal/GDPR)",
            fileLabel = "patientProfileId=$patientProfileId",
            rawText = prettyJson
        )

        val filename = "deleted_patient_${patientProfileId}_export.pdf"
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(pdfBytes)
    }

    private fun buildDeletedPatientExportDto(patientProfileId: Long): DeletedPatientExportDto {
        val profile = patientProfiles.findByIdWithUser(patientProfileId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Patient profile not found: $patientProfileId")
        }

        val user = profile.user ?: throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Patient profile is not linked to an account"
        )

        if (user.role != Role.PATIENT || user.accountStatus != User.AccountStatus.DELETED) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Patient profile does not belong to a deleted patient"
            )
        }

        val patientId = profile.id ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient ID not found")
        val appointmentsForPatient = runCatching {
            appointments.findByPatientIdWithDoctorForExport(patientId).mapNotNull { a ->
                runCatching { toAppointmentExport(a) }
                    .onFailure { ex ->
                        log.warn(
                            "DELETED_PATIENT_EXPORT: skip appointment id={} for patientId={}: {}",
                            a.id,
                            patientId,
                            ex.message
                        )
                    }
                    .getOrNull()
            }
        }.onFailure {
            log.error("DELETED_PATIENT_EXPORT: failed to load appointments for patientId={}", patientId, it)
        }.getOrDefault(emptyList())

        val documents = runCatching {
            patientDocumentService.listForPatientSelf(patientId)
        }.onFailure {
            log.error("DELETED_PATIENT_EXPORT: failed to load documents for patientId={}", patientId, it)
        }.getOrDefault(emptyList())

        val forms = runCatching {
            patientFormService.list(patientId)
        }.onFailure {
            log.error("DELETED_PATIENT_EXPORT: failed to load forms for patientId={}", patientId, it)
        }.getOrDefault(emptyList())

        val consultationNotes = runCatching {
            consultationNoteRepository.findByPatientId(patientId).mapNotNull { n ->
                runCatching { toConsultationExport(n) }
                    .onFailure { ex ->
                        log.warn(
                            "DELETED_PATIENT_EXPORT: skip consultation note id={} for patientId={}: {}",
                            n.id,
                            patientId,
                            ex.message
                        )
                    }
                    .getOrNull()
            }
        }.onFailure {
            log.error("DELETED_PATIENT_EXPORT: failed to load consultation notes for patientId={}", patientId, it)
        }.getOrDefault(emptyList())

        val aiDraftNotes = runCatching {
            aiDraftNoteRepository.findByPatientId(patientId).mapNotNull { n ->
                runCatching { toAiDraftExport(n) }
                    .onFailure { ex ->
                        log.warn(
                            "DELETED_PATIENT_EXPORT: skip AI draft id={} for patientId={}: {}",
                            n.id,
                            patientId,
                            ex.message
                        )
                    }
                    .getOrNull()
            }
        }.onFailure {
            log.error("DELETED_PATIENT_EXPORT: failed to load AI draft notes for patientId={}", patientId, it)
        }.getOrDefault(emptyList())

        return DeletedPatientExportDto(
            userId = user.id,
            patientProfileId = patientId,
            deletedAt = user.deletedAt?.toString(),
            deletionRequestedAt = user.deletionRequestedAt?.toString(),
            patientProfile = toPatientProfileExport(profile),
            appointments = appointmentsForPatient,
            documents = documents,
            forms = forms,
            consultationNotes = consultationNotes,
            aiDraftNotes = aiDraftNotes
        )
    }

    private fun renderTextPdf(title: String, fileLabel: String, rawText: String): ByteArray {
        // Simple text-to-PDF using PDFBox (Helvetica font). We sanitize non-ASCII chars to avoid missing glyphs.
        val safeTitle = toAsciiSafe(title)
        val safeLabel = toAsciiSafe(fileLabel)
        val safeText = toAsciiSafe(rawText)

        val maxCharsPerLine = 100
        val leading = 14f
        val marginTop = 50f
        val marginBottom = 50f
        val startX = 50f

        val lines = buildList {
            add(safeTitle)
            add(safeLabel)
            add("=".repeat(60))
            addAll(wrapLines(safeText, maxCharsPerLine))
        }

        PDDocument().use { doc ->
            var page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            var y = page.mediaBox.height - marginTop

            fun openStream(): PDPageContentStream {
                return PDPageContentStream(doc, page)
            }

            val font = PDType1Font(FontName.HELVETICA)
            var contentStream = openStream()
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

                    contentStream = openStream()
                    contentStream.setFont(font, 11f)
                    contentStream.beginText()
                    contentStream.newLineAtOffset(startX, y)
                }

                contentStream.showText(sanitizePdfWinAnsiLine(line))
                contentStream.newLineAtOffset(0f, -leading)
                y -= leading
            }

            contentStream.endText()
            contentStream.close()

            val baos = java.io.ByteArrayOutputStream()
            doc.save(baos)
            return baos.toByteArray()
        }
    }

    private fun wrapLines(text: String, maxCharsPerLine: Int): List<String> {
        val out = mutableListOf<String>()
        val srcLines = text.split('\n')
        for (src in srcLines) {
            var s = src
            if (s.isEmpty()) {
                out.add("")
                continue
            }
            while (s.length > maxCharsPerLine) {
                out.add(s.substring(0, maxCharsPerLine))
                s = s.substring(maxCharsPerLine)
            }
            out.add(s)
        }
        return out
    }

    private fun toAsciiSafe(s: String): String {
        return s.map { ch ->
            if (ch.code <= 127) ch else '?'
        }.joinToString("")
    }

    /** Helvetica WinAnsi cannot represent all ASCII; keep printable subset for PDFBox showText. */
    private fun sanitizePdfWinAnsiLine(line: String): String {
        return line.map { ch ->
            val c = ch.code
            if (c in 32..126) ch else '?'
        }.joinToString("")
    }

    private fun toPatientProfileExport(p: PatientProfile): PatientProfileExportDto {
        return PatientProfileExportDto(
            id = requireNotNull(p.id),
            fullName = p.fullName,
            birthDate = p.birthDate?.toString(),
            language = p.language,
            timeZone = p.timeZone,
            chronicDisease = p.chronicDisease,
            address = p.address,
            photoUrl = p.photoUrl
        )
    }

    private fun toAppointmentExport(a: Appointment): AppointmentExportDto {
        val doctorName = listOf(a.doctor.firstName, a.doctor.lastName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { "Doctor" }

        return AppointmentExportDto(
            id = a.id,
            doctorId = a.doctor.id,
            doctorName = doctorName,
            startAt = a.startAt.toString(),
            endAt = a.endAt.toString(),
            location = a.location,
            reason = a.reason,
            status = a.status.name,
            signatureRequested = a.signatureRequested,
            patientSignedAt = a.patientSignedAt?.toString(),
        )
    }

    private fun toConsultationExport(n: ConsultationNote): ConsultationNoteExportDto {
        return ConsultationNoteExportDto(
            id = n.id,
            doctorId = n.doctorId,
            patientId = n.patientId,
            appointmentId = n.appointmentId,
            aiDraftNoteId = n.aiDraftNoteId,
            subjective = n.subjective,
            assessment = n.assessment,
            plan = n.plan,
            body = n.body,
            createdAt = n.createdAt.toString(),
            source = n.source,
        )
    }

    private fun toAiDraftExport(n: AiDraftNote): AiDraftNoteExportDto {
        return AiDraftNoteExportDto(
            id = n.id,
            doctorId = n.doctorId,
            patientId = n.patientId,
            consultationId = n.consultationId,
            aiLabel = n.aiLabel,
            aiResponseText = n.aiResponseText,
            icdSuggestionsJson = n.icdSuggestionsJson,
            createdAt = n.createdAt.toString(),
            status = n.status.name,
            modelVersion = n.modelVersion,
            promptVersion = n.promptVersion
        )
    }
}

