package com.shifa.patients.api

import com.shifa.documents.application.FiledDocument
import com.shifa.patients.application.PatientFormService
import com.shifa.shared.security.DoctorPrincipal
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The 025-2 rendered on the server (BE-01): a signed-in doctor who may see the patient (`assertPatientVisible`, the
 * patient directory's rule: 404 otherwise), and the form must belong to [patientId] (400, as `GET …/forms/{formId}`).
 */
@RestController
@RequestMapping("/api/patients")
class PatientFormPdfController(
    private val formService: PatientFormService,
    private val clinicAccess: com.shifa.clinics.application.ClinicAccessService,
) {

    /** The form as a PDF; read-only. */
    @GetMapping("/{patientId}/forms/{formId}/pdf")
    fun formPdf(
        @PathVariable patientId: Long,
        @PathVariable formId: Long,
        @AuthenticationPrincipal principal: DoctorPrincipal,
    ): ResponseEntity<ByteArray> {
        clinicAccess.assertPatientVisible(principal, patientId)
        val pdf = formService.renderPdf(patientId, formId)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(pdf.fileName).build().toString())
            .body(pdf.bytes)
    }

    /** Renders the PDF and files it as the form's FORM_025_2 document (replacing the one filed before). */
    @PostMapping("/{patientId}/forms/{formId}/pdf-document")
    fun formPdfDocument(
        @PathVariable patientId: Long,
        @PathVariable formId: Long,
        @AuthenticationPrincipal principal: DoctorPrincipal,
    ): FiledDocument {
        clinicAccess.assertPatientVisible(principal, patientId)
        return formService.filePdfDocument(patientId, formId, principal.profile)
    }
}
