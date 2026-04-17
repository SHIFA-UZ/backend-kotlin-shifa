package com.shifa.web

import com.shifa.domain.DocumentAccessRequest
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.security.PatientPrincipal
import com.shifa.service.DocumentAccessService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus

@RestController
@RequestMapping("/api/document-access-requests")
class DocumentAccessController(
    private val documentAccessService: DocumentAccessService,
    private val appointmentRepo: AppointmentRepository,
    private val patientProfiles: PatientProfileRepository
) {

    /**
     * POST /api/document-access-requests/{requestId}/approve
     * Called by the document owner (doctor or patient) to approve an access request.
     */
    @PostMapping("/{requestId}/approve")
    fun approve(
        @PathVariable requestId: Long,
        @AuthenticationPrincipal principal: Any
    ): ResponseEntity<DocumentAccessRequestDto> {
        val (ownerType, ownerId) = resolveOwner(principal)
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized")
        val request = documentAccessService.approve(requestId, ownerType, ownerId)
        return ResponseEntity.ok(toDto(request))
    }

    /**
     * POST /api/document-access-requests/{requestId}/reject
     * Called by the document owner to reject an access request.
     */
    @PostMapping("/{requestId}/reject")
    fun reject(
        @PathVariable requestId: Long,
        @AuthenticationPrincipal principal: Any
    ): ResponseEntity<DocumentAccessRequestDto> {
        val (ownerType, ownerId) = resolveOwner(principal)
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized")
        val request = documentAccessService.reject(requestId, ownerType, ownerId)
        return ResponseEntity.ok(toDto(request))
    }

    private fun resolveOwner(principal: Any): Pair<DocumentAccessRequest.OwnerType, Long>? {
        return when (principal) {
            is com.shifa.security.DoctorPrincipal -> DocumentAccessRequest.OwnerType.doctor to principal.profile.id
            is PatientPrincipal -> {
                val patient = principal.user.phone?.let { patientProfiles.findByPhone(it).orElse(null) }
                    ?: principal.user.email?.let { patientProfiles.findByEmail(it).orElse(null) }
                    ?: return null
                val pid = patient.id ?: return null
                DocumentAccessRequest.OwnerType.patient to pid
            }
            else -> null
        }
    }

    private fun toDto(r: DocumentAccessRequest): DocumentAccessRequestDto = DocumentAccessRequestDto(
        id = r.id,
        documentId = r.document.id!!,
        requestingDoctorId = r.requestingDoctor.id,
        ownerType = r.ownerType.name,
        ownerId = r.ownerId,
        status = r.status.name,
        createdAt = r.createdAt.toString()
    )

    data class DocumentAccessRequestDto(
        val id: Long,
        val documentId: Long,
        val requestingDoctorId: Long,
        val ownerType: String,
        val ownerId: Long,
        val status: String,
        val createdAt: String
    )
}
