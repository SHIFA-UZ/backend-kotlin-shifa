package com.shifa.patients.api

import com.shifa.shared.security.DoctorPrincipal
import com.shifa.patients.application.PatientFormService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import com.shifa.shared.api.Pages
import org.springframework.data.web.PageableDefault
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Page

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

    /** One page of [listForms] (roadmap P6). */
    @GetMapping("/{patientId}/forms/page")
    fun listFormsPage(
        @PathVariable patientId: Long,
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @org.springdoc.core.annotations.ParameterObject @PageableDefault(size = 20) pageable: Pageable,
    ): Page<PatientFormDto> = Pages.of(formService.list(patientId), pageable)

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
        val form = formService.create(patientId, request, doctorName, clinic, principal.profile)
        return ResponseEntity.ok(form)
    }

    @PutMapping("/{patientId}/forms/{formId}/request-signature")
    fun requestPatientSignature(
        @PathVariable patientId: Long,
        @PathVariable formId: Long,
        @AuthenticationPrincipal principal: DoctorPrincipal
    ): ResponseEntity<PatientFormDto> {
        val existing = formService.getById(formId)
        if (existing.patientId != patientId) {
            return ResponseEntity.badRequest().build()
        }
        return try {
            ResponseEntity.ok(
                formService.requestPatientSignature(patientId, formId, principal.profile)
            )
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.message, e)
        }
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
        val form = formService.update(formId, request, doctorName, clinic, principal.profile)
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