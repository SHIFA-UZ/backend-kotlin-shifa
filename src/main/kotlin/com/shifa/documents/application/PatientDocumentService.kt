package com.shifa.documents.application

import com.shifa.appointments.domain.Appointment
import com.shifa.documents.api.PatientDocumentDto
import com.shifa.documents.domain.PatientDocument
import com.shifa.documents.domain.PatientDocumentCategory
import com.shifa.documents.infrastructure.DocumentAccessGrantRepository
import com.shifa.documents.infrastructure.PatientDocumentRepository
import java.time.LocalDate
import org.slf4j.LoggerFactory
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

@Service
class PatientDocumentService(
    private val profiles: com.shifa.patients.application.PatientProfiles,
    private val docs: PatientDocumentRepository,
    private val storage: PatientDocumentStorageService,
    private val doctorProfiles: com.shifa.doctors.application.DoctorProfiles,
    private val accessGrants: DocumentAccessGrantRepository,
) {
    private val log = LoggerFactory.getLogger(PatientDocumentService::class.java)

    private fun safeFilePathOf(d: PatientDocument): String? {
        return runCatching { d.filePath }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun doctorDisplayNameOrFallback(doctor: com.shifa.doctors.domain.DoctorProfile): String {
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
            creatorLabel = creatorLabel,
            category = d.category,
            isSharedWithTeam = d.isSharedWithTeam,
            uploadedByPatient = d.uploadedByPatientProfile != null,
            creatorType = when {
                d.uploadedByDoctor != null -> "DOCTOR"
                d.uploadedByPatientProfile != null -> "PATIENT"
                else -> "UNKNOWN"
            },
            appointmentId = d.appointment?.id,
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
            creatorLabel = creatorLabel,
            category = d.category,
            isSharedWithTeam = d.isSharedWithTeam,
            uploadedByPatient = d.uploadedByPatientProfile != null,
            creatorType = when {
                d.uploadedByDoctor != null -> "DOCTOR"
                d.uploadedByPatientProfile != null -> "PATIENT"
                else -> "UNKNOWN"
            },
            appointmentId = d.appointment?.id,
        )
    }

    /**
     * Visibility rules:
     *   1. Documents marked as shared with the patient's care team are visible
     *      to every doctor that already has patient access (patient uploads,
     *      and doctor uploads tagged with a medical-result category).
     *   2. Otherwise the creator can always view their own upload.
     *   3. Otherwise an explicit access grant is required.
     */
    private fun canDoctorViewDocument(d: PatientDocument, doctorId: Long): Boolean {
        if (d.isSharedWithTeam) return true
        if (d.uploadedByDoctor?.id == doctorId) return true
        if (d.id != null && accessGrants.existsByDocument_IdAndDoctor_Id(d.id!!, doctorId)) return true
        return false
    }

    /**
     * Compute team-visibility for a doctor upload. Doctor uploads are only
     * shared automatically when tagged with a medical-result category;
     * doctor-private categories (notes, forms, remote-task documents) and
     * untagged uploads stay restricted.
     */
    private fun shouldShareDoctorUpload(category: PatientDocumentCategory?): Boolean {
        return category?.isMedicalResult == true
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

    private fun uploadKind(isChatAttachment: Boolean) =
        if (isChatAttachment) com.shifa.shared.UploadPolicy.Kind.CHAT_ATTACHMENT else com.shifa.shared.UploadPolicy.Kind.DOCUMENT

    /** Upload a document, persist a row in patient_documents, and return DTO. */
    fun upload(
        patientId: Long,
        uploadingDoctorId: Long,
        file: MultipartFile,
        title: String?,
        date: LocalDate?,
        isChatAttachment: Boolean = false,
        category: String? = null,
        appointment: Appointment? = null,
    ): PatientDocumentDto {
        val p = profiles.findById(patientId).orElseThrow()
        val doctor = doctorProfiles.findById(uploadingDoctorId).orElseThrow()
        val baseNameForFile = title?.takeIf { it.isNotBlank() }
            ?: (file.originalFilename ?: "document")
        val saved = storage.saveDocument(patientId, file, baseNameForFile, uploadKind(isChatAttachment))
        val resolvedCategory = PatientDocumentCategory.fromCodeOrNull(category)
        val isAppointmentNote = resolvedCategory == PatientDocumentCategory.APPOINTMENT_NOTE
        if (isAppointmentNote && appointment != null) {
            val existing = docs.findByAppointmentId(appointment.id)
                .firstOrNull { it.category == PatientDocumentCategory.APPOINTMENT_NOTE.code }
            if (existing != null) {
                val oldPath = existing.filePath
                existing.filePath = saved.filePathRelative
                existing.title = title?.takeIf { it.isNotBlank() } ?: existing.title
                if (date != null) existing.date = date
                existing.appointment = appointment
                val updated = docs.save(existing)
                if (oldPath.isNotBlank() && oldPath != saved.filePathRelative) {
                    storage.deleteFile(oldPath)
                }
                return toDto(updated, uploadingDoctorId)
            }
        }
        val entity = PatientDocument(
            title = title?.takeIf { it.isNotBlank() } ?: baseNameForFile,
            date = date ?: LocalDate.now(),
            filePath = saved.filePathRelative,
            patient = p,
            isChatAttachment = isChatAttachment,
            uploadedByDoctor = doctor,
            // Chat attachments stay private (legacy behaviour); other uploads
            // become team-visible only when the doctor tags them as a medical
            // result (MRI, blood test, ultrasound, ...).
            category = resolvedCategory?.code,
            isSharedWithTeam = !isChatAttachment && shouldShareDoctorUpload(resolvedCategory),
            appointment = appointment,
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
        isChatAttachment: Boolean = false,
        category: String? = null
    ): PatientDocumentDto {
        val p = profiles.findById(patientId).orElseThrow { IllegalArgumentException("Patient not found: $patientId") }
        val baseNameForFile = title?.takeIf { it.isNotBlank() }
            ?: (file.originalFilename ?: "document")
        val saved = storage.saveDocument(patientId, file, baseNameForFile, uploadKind(isChatAttachment))
        val resolvedCategory = PatientDocumentCategory.fromCodeOrNull(category)
        // Pre-appointment drafts stay private until linked to a booked appointment.
        val shareWithTeam = !isChatAttachment &&
            resolvedCategory != PatientDocumentCategory.PRE_APPOINTMENT
        val entity = PatientDocument(
            title = title?.takeIf { it.isNotBlank() } ?: baseNameForFile,
            date = date ?: LocalDate.now(),
            filePath = saved.filePathRelative,
            patient = p,
            isChatAttachment = isChatAttachment,
            uploadedByDoctor = null,
            uploadedByPatientProfile = p,
            category = resolvedCategory?.code,
            isSharedWithTeam = shareWithTeam
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

    /**
     * Link patient-owned documents to an appointment (pre-appointment attachments).
     * Validates ownership and appointment patient match; marks docs team-shared.
     * Returns the number of documents linked.
     */
    /** Document ids attached to each of [appointmentIds], one projection query (patient-app ask 13). */
    fun documentIdsByAppointment(appointmentIds: Collection<Long>): Map<Long, List<Long>> {
        if (appointmentIds.isEmpty()) return emptyMap()
        return docs.findIdPairsByAppointmentIds(appointmentIds)
            .groupBy({ (it[0] as Number).toLong() }, { (it[1] as Number).toLong() })
    }

    @Transactional
    fun linkDocumentsToAppointment(
        patientId: Long,
        appointment: Appointment,
        documentIds: List<Long>,
    ): Int {
        if (documentIds.isEmpty()) return 0
        val apptPatientId = appointment.patient?.id
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Appointment has no patient")
        if (apptPatientId != patientId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Appointment does not belong to this patient")
        }
        val uniqueIds = documentIds.distinct()
        var linked = 0
        for (docId in uniqueIds) {
            val doc = docs.findById(docId).orElse(null)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found: $docId")
            if (doc.patient?.id != patientId) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "Document $docId does not belong to this patient")
            }
            val uploaderPatientId = doc.uploadedByPatientProfile?.id
            if (uploaderPatientId != patientId) {
                throw ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only documents you uploaded can be attached to an appointment"
                )
            }
            doc.appointment = appointment
            if (doc.category.isNullOrBlank()) {
                doc.category = PatientDocumentCategory.PRE_APPOINTMENT.code
            }
            doc.isSharedWithTeam = true
            docs.save(doc)
            linked++
        }
        return linked
    }

    fun listForAppointment(appointmentId: Long): List<PatientDocument> =
        docs.findByAppointmentId(appointmentId)

    // ---- The door for other features (roadmap Phase 5 step 4): nothing outside `documents` touches the repository.
    // Names match the former repository calls on purpose; callers only changed the receiver.

    fun save(document: PatientDocument): PatientDocument = docs.save(document)
    fun findById(id: Long): java.util.Optional<PatientDocument> = docs.findById(id)
    fun listForPatient(patientId: Long): List<PatientDocument> = docs.listForPatient(patientId)
    fun findByAppointmentId(appointmentId: Long): List<PatientDocument> = docs.findByAppointmentId(appointmentId)
    fun countByAppointmentIds(appointmentIds: Collection<Long>): List<Array<Any>> = docs.countByAppointmentIds(appointmentIds)
    fun countDocumentsByDoctorPatientsInDateRange(
        doctorId: Long,
        apptStart: java.time.Instant,
        apptEnd: java.time.Instant,
        docStart: java.time.LocalDate,
        docEnd: java.time.LocalDate,
    ): Long = docs.countDocumentsByDoctorPatientsInDateRange(doctorId, apptStart, apptEnd, docStart, docEnd)
    fun countByUploadedDoctorAndDocumentDateBetween(doctorId: Long, from: java.time.LocalDate, toInclusive: java.time.LocalDate): Long =
        docs.countByUploadedDoctorAndDocumentDateBetween(doctorId, from, toInclusive)
    fun countUploadedByDoctorAllTime(doctorId: Long): Long = docs.countUploadedByDoctorAllTime(doctorId)
    fun listUploadedByDoctorInDocumentDateRange(doctorId: Long, from: java.time.LocalDate, toInclusive: java.time.LocalDate): List<PatientDocument> =
        docs.listUploadedByDoctorInDocumentDateRange(doctorId, from, toInclusive)
    @Transactional
    fun clearUploadedByDoctor(doctorId: Long): Int = docs.clearUploadedByDoctor(doctorId)
    /** Rows only; the stored files are kept (account deletion keeps today's behaviour). */
    @Transactional
    fun deleteAllForPatient(patientId: Long) = docs.deleteAll(docs.listForPatient(patientId))
}
