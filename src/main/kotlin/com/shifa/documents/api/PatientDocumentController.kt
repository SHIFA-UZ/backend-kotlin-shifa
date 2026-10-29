// src/main/kotlin/com/shifa/documents/api/PatientDocumentController.kt
package com.shifa.documents.api

import com.shifa.appointments.application.AppointmentQueries
import com.shifa.patients.application.DoctorPatientAccessService
import com.shifa.shared.security.ClinicStaffPrincipal
import com.shifa.shared.security.DoctorPrincipal
import com.shifa.clinics.application.ClinicAccessService
import com.shifa.documents.application.DocumentAccessService
import com.shifa.documents.application.PatientDocumentService
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
    private val appointments: AppointmentQueries,
    private val patientAccess: DoctorPatientAccessService,
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

    private fun ensurePatientDocumentAccess(principal: Any, patientId: Long, clinicId: Long?) {
        clinicAccess.assertPracticeActor(principal)
        when (principal) {
            is DoctorPrincipal -> {
                val doctorId = principal.profile.id
                if (patientAccess.doctorHasAppointmentOrCreatedPatient(doctorId, patientId)) return
                if (!patientAccess.patientExists(patientId)) {
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
        @RequestParam(name = "appointmentId", required = false) appointmentId: Long?,
    ): ResponseEntity<PatientDocumentDto> {
        ensurePatientDocumentAccess(principal, patientId, clinicId)
        val uploadingDoctorId = effectiveDoctorProfileId(principal, clinicId)
        val docDate = date?.let(LocalDate::parse)
        val appointment = appointmentId?.let { id ->
            appointments.ownedByPatient(id, patientId).also { clinicAccess.assertCanAccessAppointmentResource(principal, it.doctor.id) }
        }
        val dto = service.upload(
            patientId = patientId,
            uploadingDoctorId = uploadingDoctorId,
            file = file,
            title = title,
            date = docDate,
            isChatAttachment = isChatAttachment,
            category = category,
            appointment = appointment,
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
