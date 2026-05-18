// src/main/kotlin/com/shifa/web/PatientDocumentController.kt
package com.shifa.web

import com.shifa.repo.AppointmentRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.security.ClinicStaffPrincipal
import com.shifa.security.DoctorPrincipal
import com.shifa.service.ClinicAccessService
import com.shifa.service.DocumentAccessService
import com.shifa.service.PatientDocumentService
import com.shifa.web.dto.PatientDocumentDto
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate

@RestController
@RequestMapping("/api/patients")
class PatientDocumentController(
    private val service: PatientDocumentService,
    private val appointmentRepo: AppointmentRepository,
    private val patientsRepo: PatientProfileRepository,
    private val documentAccessService: DocumentAccessService,
    private val clinicAccess: ClinicAccessService,
) {

    /**
     * Resolves the doctor profile id used for document list/DTO visibility and uploads.
     * Staff acts through a delegate doctor at the clinic ([clinicId] required for staff).
     */
    private fun effectiveDoctorProfileId(principal: Any, clinicId: Long?): Long = when (principal) {
        is DoctorPrincipal -> principal.profile.id
        is ClinicStaffPrincipal -> {
            val cid = clinicId
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "clinicId is required")
            clinicAccess.delegateDoctorProfileIdForClinic(cid)
        }
        else -> throw ResponseStatusException(HttpStatus.FORBIDDEN)
    }

    /** Strict: appointment with this doctor or created by this doctor. */
    private fun strictDoctorPatientAccess(doctorId: Long, patientId: Long): Boolean {
        if (appointmentRepo.existsByDoctorIdAndPatientId(doctorId, patientId)) return true
        val patient = patientsRepo.findById(patientId).orElse(null) ?: return false
        return patient.createdByDoctor?.id == doctorId
    }

    private fun ensurePatientDocumentAccess(principal: Any, patientId: Long, clinicId: Long?) {
        clinicAccess.assertPracticeActor(principal)
        when (principal) {
            is DoctorPrincipal -> {
                val doctorId = principal.profile.id
                if (strictDoctorPatientAccess(doctorId, patientId)) return
                if (!patientsRepo.findById(patientId).isPresent) {
                    throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
                }
                if (clinicId != null) {
                    clinicAccess.assertPatientLinkedToClinic(principal, patientId, clinicId)
                    return
                }
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "Patient not found")
            }
            is ClinicStaffPrincipal -> {
                val cid = clinicId
                    ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "clinicId is required")
                clinicAccess.assertPatientLinkedToClinic(principal, patientId, cid)
            }
            else -> throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
    }

    @GetMapping("/{patientId}/documents")
    fun list(
        @PathVariable patientId: Long,
        @AuthenticationPrincipal principal: Any,
        @RequestParam(name = "clinicId", required = false) clinicId: Long?,
    ): ResponseEntity<List<PatientDocumentDto>> {
        ensurePatientDocumentAccess(principal, patientId, clinicId)
        val doctorId = effectiveDoctorProfileId(principal, clinicId)
        return ResponseEntity.ok(service.list(patientId, doctorId))
    }

    @PostMapping("/{patientId}/documents")
    fun upload(
        @PathVariable patientId: Long,
        @AuthenticationPrincipal principal: Any,
        @RequestParam("file") file: MultipartFile,
        @RequestParam("title", required = false) title: String?,
        @RequestParam("date", required = false) date: String?,
        @RequestParam("isChatAttachment", required = false, defaultValue = "false") isChatAttachment: Boolean,
        @RequestParam("category", required = false) category: String?,
        @RequestParam(name = "clinicId", required = false) clinicId: Long?,
    ): ResponseEntity<PatientDocumentDto> {
        ensurePatientDocumentAccess(principal, patientId, clinicId)
        val uploadingDoctorId = effectiveDoctorProfileId(principal, clinicId)
        val docDate = date?.let(LocalDate::parse)
        val dto = service.upload(
            patientId = patientId,
            uploadingDoctorId = uploadingDoctorId,
            file = file,
            title = title,
            date = docDate,
            isChatAttachment = isChatAttachment,
            category = category
        )
        return ResponseEntity.ok(dto)
    }

    @PutMapping("/{patientId}/documents/{documentId}")
    fun updateDocument(
        @PathVariable patientId: Long,
        @PathVariable documentId: Long,
        @AuthenticationPrincipal principal: Any,
        @RequestParam("file") file: MultipartFile,
        @RequestParam(name = "clinicId", required = false) clinicId: Long?,
    ): ResponseEntity<PatientDocumentDto> {
        ensurePatientDocumentAccess(principal, patientId, clinicId)
        val doctorId = effectiveDoctorProfileId(principal, clinicId)
        val dto = service.updateFile(documentId, doctorId, file)
        return ResponseEntity.ok(dto)
    }

    @PostMapping("/{patientId}/documents/{documentId}/request-access")
    fun requestAccess(
        @PathVariable patientId: Long,
        @PathVariable documentId: Long,
        @AuthenticationPrincipal principal: Any,
        @RequestParam(name = "clinicId", required = false) clinicId: Long?,
    ): ResponseEntity<Map<String, Any>> {
        ensurePatientDocumentAccess(principal, patientId, clinicId)
        val requestingDoctorId = effectiveDoctorProfileId(principal, clinicId)
        val request = documentAccessService.requestAccess(documentId, requestingDoctorId)
        return ResponseEntity.ok(mapOf(
            "id" to request.id,
            "documentId" to request.document.id!!,
            "status" to request.status.name,
            "createdAt" to request.createdAt.toString()
        ))
    }

    @GetMapping("/{patientId}/documents/{documentId}/download")
    fun download(
        @PathVariable patientId: Long,
        @PathVariable documentId: Long,
        @AuthenticationPrincipal principal: Any,
        @RequestParam(name = "clinicId", required = false) clinicId: Long?,
    ): ResponseEntity<org.springframework.core.io.Resource> {
        ensurePatientDocumentAccess(principal, patientId, clinicId)
        val doctorId = effectiveDoctorProfileId(principal, clinicId)
        val resource = service.getDocumentResourceForDoctor(patientId, documentId, doctorId)
            ?: return ResponseEntity.notFound().build()
        val filename = resource.filename ?: "document.pdf"
        val contentType: MediaType = when {
            filename.lowercase().endsWith(".pdf") -> MediaType.APPLICATION_PDF
            filename.lowercase().endsWith(".jpg") || filename.lowercase().endsWith(".jpeg") -> MediaType.IMAGE_JPEG
            filename.lowercase().endsWith(".png") -> MediaType.IMAGE_PNG
            filename.lowercase().endsWith(".gif") -> MediaType.IMAGE_GIF
            filename.lowercase().endsWith(".webp") -> MediaType("image", "webp")
            else -> MediaType.APPLICATION_OCTET_STREAM
        }
        val headers = HttpHeaders().apply {
            setContentType(contentType)
            setContentDisposition(ContentDisposition.inline().filename(filename).build())
        }
        return ResponseEntity.ok()
            .headers(headers)
            .body(resource)
    }
}
