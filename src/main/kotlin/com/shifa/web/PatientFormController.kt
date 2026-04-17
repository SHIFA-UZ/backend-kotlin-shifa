package com.shifa.web

import com.shifa.security.DoctorPrincipal
import com.shifa.service.PatientFormService
import com.shifa.web.dto.PatientFormDto
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/patients")
class PatientFormController(
    private val formService: PatientFormService
) {

    @GetMapping("/{patientId}/forms")
    fun listForms(
        @PathVariable patientId: Long,
        @AuthenticationPrincipal principal: DoctorPrincipal
    ): ResponseEntity<List<PatientFormDto>> {
        val forms = formService.list(patientId)
        return ResponseEntity.ok(forms)
    }

    @GetMapping("/{patientId}/forms/{formId}")
    fun getForm(
        @PathVariable patientId: Long,
        @PathVariable formId: Long,
        @AuthenticationPrincipal principal: DoctorPrincipal
    ): ResponseEntity<PatientFormDto> {
        val form = formService.getById(formId)
        if (form.patientId != patientId) {
            return ResponseEntity.badRequest().build()
        }
        return ResponseEntity.ok(form)
    }

    @PostMapping("/{patientId}/forms")
    fun createForm(
        @PathVariable patientId: Long,
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody request: PatientFormDto
    ): ResponseEntity<PatientFormDto> {
        val doctorName = "${principal.profile.lastName} ${principal.profile.firstName}".trim()
        val clinic = principal.profile.clinic
        val form = formService.create(patientId, request, doctorName, clinic)
        return ResponseEntity.ok(form)
    }

    @PutMapping("/{patientId}/forms/{formId}")
    fun updateForm(
        @PathVariable patientId: Long,
        @PathVariable formId: Long,
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody request: PatientFormDto
    ): ResponseEntity<PatientFormDto> {
        val existing = formService.getById(formId)
        if (existing.patientId != patientId) {
            return ResponseEntity.badRequest().build()
        }

        val doctorName = "${principal.profile.lastName} ${principal.profile.firstName}".trim()
        val clinic = principal.profile.clinic
        val form = formService.update(formId, request, doctorName, clinic)
        return ResponseEntity.ok(form)
    }

    @PostMapping("/{patientId}/forms/{formId}/link-document")
    fun linkDocument(
        @PathVariable patientId: Long,
        @PathVariable formId: Long,
        @RequestParam documentId: Long,
        @AuthenticationPrincipal principal: DoctorPrincipal
    ): ResponseEntity<PatientFormDto> {
        val form = formService.linkDocument(formId, documentId)
        return ResponseEntity.ok(form)
    }

    @DeleteMapping("/{patientId}/forms/{formId}")
    fun deleteForm(
        @PathVariable patientId: Long,
        @PathVariable formId: Long,
        @AuthenticationPrincipal principal: DoctorPrincipal
    ): ResponseEntity<Void> {
        formService.delete(formId)
        return ResponseEntity.noContent().build()
    }
}