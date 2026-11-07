package com.shifa.auth.api

import com.shifa.auth.api.dto.ConfirmDoctorDeleteAccountRequest
import com.shifa.auth.api.dto.DoctorDeleteAccountRequestResponse
import com.shifa.auth.application.DoctorAccountDeletionService
import com.shifa.shared.security.DoctorPrincipal
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * A doctor deletes their own account in two steps: request (checks obligations, e-mails a code), then confirm with
 * the code. 409 `HAS_ACTIVE_OBLIGATIONS` lists what to resolve first in `details` (`UPCOMING_APPOINTMENTS`,
 * `ACTIVE_SUBSCRIPTION`).
 */
@RestController
@RequestMapping("/api/doctors/me/delete-account")
class DoctorAccountDeletionController(private val deletion: DoctorAccountDeletionService) {

    @PostMapping("/request")
    fun request(@AuthenticationPrincipal principal: DoctorPrincipal): DoctorDeleteAccountRequestResponse =
        deletion.request(principal.profile.id).let { DoctorDeleteAccountRequestResponse(it.maskedEmail, it.expiresInSeconds) }

    @PostMapping("/confirm")
    fun confirm(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody @Valid body: ConfirmDoctorDeleteAccountRequest,
    ): Map<String, Any> {
        deletion.confirm(principal.profile.id, body.code)
        return mapOf("success" to true)
    }
}
