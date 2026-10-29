package com.shifa.patients.api

import com.shifa.shared.security.PatientPrincipal
import com.shifa.patients.application.PatientFormService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/patients/me/forms")
class PatientMeFormController(
    private val formService: PatientFormService,
    private val currentPatient: com.shifa.patients.application.CurrentPatientResolver,
) {

    private fun currentPatientProfile(principal: PatientPrincipal): com.shifa.patients.domain.PatientProfile = currentPatient.resolve(principal)

    data class SubmitSignatureRequest(
        val signatureImageBase64: String
    )

    @GetMapping("/{formId}/for-signing")
    @Transactional(readOnly = true)
    fun getForSigning(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @PathVariable formId: Long
    ): PatientFormDto {
        val patient = currentPatientProfile(principal)
        val patientId = patient.id ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient ID not found")
        return try {
            formService.getForPatientSigning(formId, patientId)
        } catch (e: IllegalArgumentException) {
            when {
                e.message?.contains("not found", ignoreCase = true) == true ->
                    throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
                e.message?.contains("does not belong", ignoreCase = true) == true ->
                    throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message)
                else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
            }
        }
    }

    @PostMapping("/{formId}/submit-signature")
    @Transactional
    fun submitSignature(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @PathVariable formId: Long,
        @RequestBody req: SubmitSignatureRequest
    ) {
        val patient = currentPatientProfile(principal)
        val patientId = patient.id ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient ID not found")
        try {
            formService.submitPatientSignature(formId, patientId, req.signatureImageBase64)
        } catch (e: IllegalArgumentException) {
            when {
                e.message?.contains("not found", ignoreCase = true) == true ->
                    throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
                e.message?.contains("does not belong", ignoreCase = true) == true ->
                    throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message)
                e.message?.contains("already", ignoreCase = true) == true ->
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
                else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
            }
        }
    }
}
