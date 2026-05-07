package com.shifa.service

import com.shifa.domain.PatientDocument
import com.shifa.repo.DocumentAccessGrantRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.PatientDocumentRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.web.dto.PatientDocumentDto
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import org.slf4j.LoggerFactory
import java.time.LocalDate

@Service
class PatientDocumentService(
    private val profiles: PatientProfileRepository,
    private val docs: PatientDocumentRepository,
    private val storage: PatientDocumentStorageService,
    private val doctorProfiles: DoctorProfileRepository,
    private val accessGrants: DocumentAccessGrantRepository
) {
    private val log = LoggerFactory.getLogger(PatientDocumentService::class.java)

    private fun safeFilePathOf(d: PatientDocument): String? {
        return runCatching { d.filePath }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun doctorDisplayNameOrFallback(doctor: com.shifa.domain.DoctorProfile): String {
        val firstName = runCatching { doctor.firstName }.getOrDefault("")
        val lastName = runCatching { doctor.lastName }.getOrDefault("")
        val profileName = listOf(firstName, lastName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (profileName.isNotBlank()) return profileName

        val doctorId = runCatching { doctor.id }.getOrDefault(0L)
        if (doctorId > 0) return "Doctor #$doctorId"
        return "Doctor"
    }

    private fun creatorLabelOf(d: PatientDocument): String {
        val doctor = d.uploadedByDoctor
        if (doctor != null) {
            return doctorDisplayNameOrFallback(doctor)
        }
        val patient = d.uploadedByPatientProfile
        if (patient != null) {
            val fullName = runCatching { patient.fullName }.getOrDefault("").trim()
            return if (fullName.isNotEmpty()) fullName else "Patient"
        }
        return "Unknown"
    }


    /** Return documents for a patient with visibility for the given doctor (newest first). */
    fun list(patientId: Long, doctorId: Long): List<PatientDocumentDto> {
        val found = docs.listForPatient(patientId)
        return found
            .filter { safeFilePathOf(it) != null }
            .filter { !it.isChatAttachment }
            .mapNotNull { d ->
                runCatching { toDto(d, doctorId) }
                    .onFailure { ex ->
                        log.warn("Skipping invalid doctor document id={} patientId={}: {}", d.id, patientId, ex.message)
                    }
                    .getOrNull()
            }
    }

    /**
     * Return all documents for a patient when the patient is viewing their own.
     * Unlike doctors (who see only documents they uploaded or have access to), patients see
     * every document related to them: both ones they created and ones doctors created for them.
     * All entries are returned with canView=true and a viewable url.
     */
    fun listForPatientSelf(patientId: Long): List<PatientDocumentDto> {
        val found = docs.listForPatient(patientId)
        return found
            .filter { safeFilePathOf(it) != null }
            .filter { !it.isChatAttachment }
            .mapNotNull { d ->
                runCatching { toDtoForPatient(d) }
                    .onFailure { ex ->
                        log.warn("Skipping invalid patient document id={} patientId={}: {}", d.id, patientId, ex.message)
                    }
                    .getOrNull()
            }
    }

    private fun toDtoForPatient(d: PatientDocument): PatientDocumentDto {
        val id = requireNotNull(d.id) { "Document must be persisted" }
        val creatorLabel = creatorLabelOf(d)
        val filePath = safeFilePathOf(d)
        val url = if (filePath != null) storage.publicUrlFor(filePath) else null
        return PatientDocumentDto(
            id = id,
            title = d.title,
            date = d.date,
            url = url,
            canView = true,
            creatorLabel = creatorLabel
        )
    }

    private fun toDto(d: PatientDocument, doctorId: Long): PatientDocumentDto {
        val id = requireNotNull(d.id) { "Document must be persisted" }
        val canView = canDoctorViewDocument(d, doctorId)
        val creatorLabel = creatorLabelOf(d)
        val filePath = safeFilePathOf(d)
        val url = if (canView && filePath != null) storage.publicUrlFor(filePath) else null
        return PatientDocumentDto(
            id = id,
            title = d.title,
            date = d.date,
            url = url,
            canView = canView,
            creatorLabel = creatorLabel
        )
    }

    private fun canDoctorViewDocument(d: PatientDocument, doctorId: Long): Boolean {
        if (d.uploadedByDoctor?.id == doctorId) return true
        if (d.id != null && accessGrants.existsByDocument_IdAndDoctor_Id(d.id!!, doctorId)) return true
        return false
    }

    /**
     * Return only patient documents the doctor can view (creator or granted access).
     * Used by AI briefing to gather document content without exposing locked docs.
     */
    fun listDocumentsWithAccess(patientId: Long, doctorId: Long): List<PatientDocument> {
        val all = docs.listForPatient(patientId)
        return all
            .filter { safeFilePathOf(it) != null }
            .filter { !it.isChatAttachment }
            .filter { canDoctorViewDocument(it, doctorId) }
    }

    /**
     * Return the file resource for authenticated download if the doctor can view the document.
     * Used so the app can open documents with the auth token (works after access is granted).
     */
    fun getDocumentResourceForDoctor(patientId: Long, documentId: Long, doctorId: Long): Resource? {
        val list = docs.listForPatient(patientId)
        val doc = list.find { it.id == documentId } ?: return null
        if (!canDoctorViewDocument(doc, doctorId)) return null
        val filePath = safeFilePathOf(doc) ?: return null
        return storage.getFileResource(filePath)
    }

    /** Upload a document, persist a row in patient_documents, and return DTO. */
    fun upload(
        patientId: Long,
        uploadingDoctorId: Long,
        file: MultipartFile,
        title: String?,
        date: LocalDate?,
        isChatAttachment: Boolean = false
    ): PatientDocumentDto {
        val p = profiles.findById(patientId).orElseThrow()
        val doctor = doctorProfiles.findById(uploadingDoctorId).orElseThrow()
        val baseNameForFile = title?.takeIf { it.isNotBlank() }
            ?: (file.originalFilename ?: "document")
        val saved = storage.saveDocument(patientId, file, baseNameForFile)
        val entity = PatientDocument(
            title = title?.takeIf { it.isNotBlank() } ?: baseNameForFile,
            date = date ?: LocalDate.now(),
            filePath = saved.filePathRelative,
            patient = p,
            isChatAttachment = isChatAttachment,
            uploadedByDoctor = doctor
        )
        val persisted = docs.save(entity)
        return toDto(persisted, uploadingDoctorId)
    }

    /** Upload a document when the patient is uploading for themselves. */
    fun uploadByPatient(
        patientId: Long,
        file: MultipartFile,
        title: String?,
        date: LocalDate?,
        isChatAttachment: Boolean = false
    ): PatientDocumentDto {
        val p = profiles.findById(patientId).orElseThrow { IllegalArgumentException("Patient not found: $patientId") }
        val baseNameForFile = title?.takeIf { it.isNotBlank() }
            ?: (file.originalFilename ?: "document")
        val saved = storage.saveDocument(patientId, file, baseNameForFile)
        val entity = PatientDocument(
            title = title?.takeIf { it.isNotBlank() } ?: baseNameForFile,
            date = date ?: LocalDate.now(),
            filePath = saved.filePathRelative,
            patient = p,
            isChatAttachment = isChatAttachment,
            uploadedByDoctor = null,
            uploadedByPatientProfile = p
        )
        val persisted = docs.save(entity)
        return toDtoForPatient(persisted)
    }

    /** Update an existing document's file content. Only allowed if the doctor is creator or has a grant. */
    fun updateFile(
        documentId: Long,
        doctorId: Long,
        file: MultipartFile
    ): PatientDocumentDto {
        val existing = docs.findById(documentId)
            .orElseThrow { IllegalArgumentException("Document not found: $documentId") }
        if (!canDoctorViewDocument(existing, doctorId)) {
            throw IllegalArgumentException("Document not found")
        }
        val existingPath = existing.filePath
        val baseName = if (existingPath.isNotBlank()) {
            val fileName = existingPath.substringAfterLast("/")
            fileName.substringBeforeLast(".")
        } else {
            file.originalFilename ?: "document"
        }
        val saved = storage.saveDocument(
            patientId = requireNotNull(existing.patient?.id) { "Document must have a patient" },
            file = file,
            preferredBaseName = baseName
        )
        existing.filePath = saved.filePathRelative
        val updated = docs.save(existing)
        return toDto(updated, doctorId)
    }

    /**
     * Delete a document when the patient is the uploader.
     * Only documents with uploadedByPatientProfile == current patient can be deleted.
     */
    fun deleteByPatient(documentId: Long, patientId: Long) {
        val doc = docs.findById(documentId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found") }
        val uploaderPatientId = doc.uploadedByPatientProfile?.id
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only documents you uploaded can be deleted")
        if (uploaderPatientId != patientId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only documents you uploaded can be deleted")
        }
        if (doc.filePath.isNotBlank()) {
            storage.deleteFile(doc.filePath)
        }
        docs.delete(doc)
    }
}
