// src/main/kotlin/com/shifa/web/PatientDocumentController.kt
package com.shifa.web

import com.shifa.repo.AppointmentRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.security.DoctorPrincipal
import com.shifa.service.DocumentAccessService
import com.shifa.service.PatientDocumentService
import com.shifa.web.dto.PatientDocumentDto
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDate

@RestController
@RequestMapping("/api/patients")
class PatientDocumentController(
    private val service: PatientDocumentService,
    private val appointmentRepo: AppointmentRepository,
    private val patientsRepo: PatientProfileRepository,
    private val documentAccessService: DocumentAccessService
) {

    /** Allow access if the patient has an appointment with this doctor or was created by this doctor. */
    private fun ensurePatientAccess(doctorId: Long, patientId: Long) {
        if (appointmentRepo.existsByDoctorIdAndPatientId(doctorId, patientId)) return
        val patient = patientsRepo.findById(patientId).orElse(null)
            ?: throw IllegalArgumentException("Patient not found")
        if (patient.createdByDoctor?.id != doctorId) {
            throw IllegalArgumentException("Patient not found")
        }
    }

    /**
     * GET /api/patients/{patientId}/documents
     * Only allowed if the patient has an appointment with the current doctor.
     */
    @GetMapping("/{patientId}/documents")
    fun list(
        @PathVariable patientId: Long,
        @AuthenticationPrincipal principal: DoctorPrincipal
    ): ResponseEntity<List<PatientDocumentDto>> {
        ensurePatientAccess(principal.profile.id, patientId)
        return ResponseEntity.ok(service.list(patientId, principal.profile.id))
    }

    /**
     * POST /api/patients/{patientId}/documents
     */
    @PostMapping("/{patientId}/documents")
    fun upload(
        @PathVariable patientId: Long,
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestParam("file") file: MultipartFile,
        @RequestParam("title", required = false) title: String?,
        @RequestParam("date", required = false) date: String?,
        @RequestParam("isChatAttachment", required = false, defaultValue = "false") isChatAttachment: Boolean
    ): ResponseEntity<PatientDocumentDto> {
        ensurePatientAccess(principal.profile.id, patientId)
        val docDate = date?.let(LocalDate::parse)
        val dto = service.upload(
            patientId = patientId,
            uploadingDoctorId = principal.profile.id,
            file = file,
            title = title,
            date = docDate,
            isChatAttachment = isChatAttachment
        )
        return ResponseEntity.ok(dto)
    }

    /**
     * PUT /api/patients/{patientId}/documents/{documentId}
     * Update an existing document's file content.
     */
    @PutMapping("/{patientId}/documents/{documentId}")
    fun updateDocument(
        @PathVariable patientId: Long,
        @PathVariable documentId: Long,
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<PatientDocumentDto> {
        ensurePatientAccess(principal.profile.id, patientId)
        val dto = service.updateFile(documentId, principal.profile.id, file)
        return ResponseEntity.ok(dto)
    }

    /**
     * POST /api/patients/{patientId}/documents/{documentId}/request-access
     * Doctor requests access to a locked document. Creates a pending request and notifies the owner.
     */
    @PostMapping("/{patientId}/documents/{documentId}/request-access")
    fun requestAccess(
        @PathVariable patientId: Long,
        @PathVariable documentId: Long,
        @AuthenticationPrincipal principal: DoctorPrincipal
    ): ResponseEntity<Map<String, Any>> {
        ensurePatientAccess(principal.profile.id, patientId)
        val request = documentAccessService.requestAccess(documentId, principal.profile.id)
        return ResponseEntity.ok(mapOf(
            "id" to request.id,
            "documentId" to request.document.id!!,
            "status" to request.status.name,
            "createdAt" to request.createdAt.toString()
        ))
    }

    /**
     * GET /api/patients/{patientId}/documents/{documentId}/download
     * Stream the document file if the doctor can view it (creator or granted access).
     * Use this with the app's auth token so opening works after access is granted.
     */
    @GetMapping("/{patientId}/documents/{documentId}/download")
    fun download(
        @PathVariable patientId: Long,
        @PathVariable documentId: Long,
        @AuthenticationPrincipal principal: DoctorPrincipal
    ): ResponseEntity<org.springframework.core.io.Resource> {
        ensurePatientAccess(principal.profile.id, patientId)
        val resource = service.getDocumentResourceForDoctor(patientId, documentId, principal.profile.id)
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
