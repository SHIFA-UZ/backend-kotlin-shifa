package com.shifa.clinics.api

import com.shifa.auth.application.Emails
import com.shifa.clinics.api.dto.CreateInvitationRequest
import com.shifa.clinics.api.dto.CreateInvitationResponse
import com.shifa.clinics.api.dto.InvitationListItemDto
import com.shifa.clinics.application.ClinicAccessService
import com.shifa.clinics.application.InvitationKeyService
import com.shifa.clinics.domain.ClinicMembership
import com.shifa.clinics.domain.InvitationKey
import com.shifa.shared.security.ClinicStaffPrincipal
import com.shifa.shared.security.DoctorPrincipal
import jakarta.validation.Valid
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
    private val clinics: com.shifa.clinics.application.Clinics,
    private val clinicAccess: ClinicAccessService,
    private val invitationKeys: com.shifa.clinics.application.InvitationKeys,
    private val invitationKeyService: InvitationKeyService,
    private val emailSender: Emails,
) {

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
