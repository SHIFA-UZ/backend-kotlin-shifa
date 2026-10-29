package com.shifa.documents.api

import com.shifa.documents.api.dto.DocumentAccessRequestDto
import com.shifa.documents.application.DocumentAccessService
import com.shifa.documents.domain.DocumentAccessRequest
import com.shifa.patients.application.CurrentPatientResolver
import com.shifa.shared.security.DoctorPrincipal
import com.shifa.shared.security.PatientPrincipal
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/document-access-requests")
class DocumentAccessController(
    private val documentAccessService: DocumentAccessService,
    private val currentPatient: CurrentPatientResolver,
) {

    /** Called by the document owner (doctor or patient) to approve an access request. */
    @PostMapping("/{requestId}/approve")
    fun approve(@PathVariable requestId: Long, @AuthenticationPrincipal principal: Any): ResponseEntity<DocumentAccessRequestDto> {
        val (ownerType, ownerId) = owner(principal)
        return ResponseEntity.ok(DocumentAccessRequestDto.from(documentAccessService.approve(requestId, ownerType, ownerId)))
    }

    /** Called by the document owner to reject an access request. */
    @PostMapping("/{requestId}/reject")
    fun reject(@PathVariable requestId: Long, @AuthenticationPrincipal principal: Any): ResponseEntity<DocumentAccessRequestDto> {
        val (ownerType, ownerId) = owner(principal)
        return ResponseEntity.ok(DocumentAccessRequestDto.from(documentAccessService.reject(requestId, ownerType, ownerId)))
    }

    private fun owner(principal: Any): Pair<DocumentAccessRequest.OwnerType, Long> = when (principal) {
        is DoctorPrincipal -> DocumentAccessRequest.OwnerType.doctor to principal.profile.id
        is PatientPrincipal -> currentPatient.find(principal.user)?.id?.let { DocumentAccessRequest.OwnerType.patient to it }
        else -> null
    } ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized")
}
