package com.shifa.web

import com.shifa.domain.ClinicMembership
import com.shifa.domain.InvitationKey
import com.shifa.repo.ClinicRepository
import com.shifa.repo.InvitationKeyRepository
import com.shifa.security.ClinicStaffPrincipal
import com.shifa.security.DoctorPrincipal
import com.shifa.service.ClinicAccessService
import com.shifa.service.EmailSenderService
import com.shifa.service.InvitationKeyService
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class ClinicInvitationController(
    private val clinics: ClinicRepository,
    private val clinicAccess: ClinicAccessService,
    private val invitationKeys: InvitationKeyRepository,
    private val invitationKeyService: InvitationKeyService,
    private val emailSender: EmailSenderService,
) {

    data class CreateInvitationRequest(
        @field:NotBlank @field:Email val email: String,
        /** Currently only RECEPTIONIST. */
        @field:NotBlank val role: String,
        val expiresInDays: Int? = 14,
    )

    data class CreateInvitationResponse(
        val id: Long,
        val keyCode: String,
        val emailSentTo: String,
        val clinicId: Long,
        val expiresAt: String?,
    )

    data class InvitationListItemDto(
        val id: Long,
        val emailSentTo: String?,
        val consumed: Boolean,
        val expiresAt: String?,
        /** True when usable for registration (not expired, not revoked/consumed). */
        val pending: Boolean,
    )

    @PostMapping("/api/clinics/{clinicId}/invitations")
    fun createInvitation(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @RequestBody @Valid body: CreateInvitationRequest,
    ): CreateInvitationResponse {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        if (!body.role.equals(ClinicMembership.MembershipRole.RECEPTIONIST.name, ignoreCase = true)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported role")
        }
        val clinic = clinics.findById(clinicId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Clinic not found")
        }
        val creator = resolveCreatorUser(principal)
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Unsupported principal")
        val emailNorm = body.email.trim().lowercase()

        val key = invitationKeyService.generateKey(
            createdBy = creator,
            expiresInDays = body.expiresInDays ?: 14,
            purpose = InvitationKey.PURPOSE_CLINIC_RECEPTIONIST_INVITE,
            notes = "Invitation for clinic ${clinic.id}",
            clinic = clinic,
            membershipRole = ClinicMembership.MembershipRole.RECEPTIONIST,
        )

        invitationKeyService.markEmailSent(key.id, emailNorm)
        val reloaded = invitationKeys.findById(key.id).orElse(key)

        val inviterLabel = resolveInviterDisplay(principal)
        emailSender.sendClinicStaffInvitationEmail(
            toEmail = emailNorm,
            code = reloaded.keyCode,
            clinicName = clinic.name,
            inviterName = inviterLabel,
        )

        return CreateInvitationResponse(
            id = reloaded.id,
            keyCode = reloaded.keyCode,
            emailSentTo = emailNorm,
            clinicId = clinic.id,
            expiresAt = reloaded.expiresAt?.toString(),
        )
    }

    @GetMapping("/api/clinics/{clinicId}/invitations")
    fun listInvitations(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @RequestParam(required = false, defaultValue = "active") status: String,
    ): List<InvitationListItemDto> {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        val all = invitationKeys.findByClinic_IdAndPurpose(
            clinicId,
            InvitationKey.PURPOSE_CLINIC_RECEPTIONIST_INVITE,
        )
        val filtered = when (status.lowercase()) {
            "active" -> all.filter { it.isValid() }
            "all", "" -> all
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid status filter")
        }
        return filtered.map {
            InvitationListItemDto(
                id = it.id,
                emailSentTo = it.emailSentTo?.trim()?.lowercase(),
                consumed = it.consumed,
                expiresAt = it.expiresAt?.toString(),
                pending = it.isValid(),
            )
        }
    }

    @DeleteMapping("/api/clinics/{clinicId}/invitations/{invitationId}")
    fun revokeInvitation(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @PathVariable invitationId: Long,
    ) {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        val existing = invitationKeys.findByIdAndClinic_Id(invitationId, clinicId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found")
        if (existing.consumed || !existing.isValid()) {
            return
        }
        invitationKeyService.revokeKey(invitationId)
    }

    private fun resolveCreatorUser(principal: Any) = when (principal) {
        is DoctorPrincipal -> principal.profile.user
        is ClinicStaffPrincipal -> principal.user
        else -> null
    }

    private fun resolveInviterDisplay(principal: Any): String = when (principal) {
        is DoctorPrincipal -> {
            val p = principal.profile
            "${p.firstName} ${p.lastName}".trim()
                .ifBlank { p.user.email ?: p.user.phone ?: "Someone" }
        }
        is ClinicStaffPrincipal -> {
            val u = principal.user
            val n = "${u.staffFirstName ?: ""} ${u.staffLastName ?: ""}".trim()
            n.ifBlank { u.email ?: u.phone ?: "Someone" }
        }
        else -> "Someone"
    }
}
