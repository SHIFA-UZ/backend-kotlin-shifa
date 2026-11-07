package com.shifa.notifications.api

import com.shifa.notifications.api.dto.DoctorPushTokenRequest
import com.shifa.notifications.application.DoctorPushTokens
import com.shifa.shared.security.DoctorPrincipal
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * A doctor's push devices: each signed-in device adds its token (at sign-in and whenever FCM refreshes it) and removes
 * it at sign-out, so every device gets each push and signing out on one leaves the others ringing. The single-token
 * `PUT /api/doctors/me/fcm-token` stays for the Flutter build.
 */
@RestController
@RequestMapping("/api/doctors/me/fcm-tokens")
class DoctorPushTokenController(private val pushTokens: DoctorPushTokens) {

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun add(@AuthenticationPrincipal principal: DoctorPrincipal, @RequestBody @Valid req: DoctorPushTokenRequest) {
        pushTokens.register(principal.profile.id, req.token.trim(), req.platform)
    }

    /** Idempotent: an unknown token, or another doctor's, is a no-op. */
    @DeleteMapping("/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(@AuthenticationPrincipal principal: DoctorPrincipal, @PathVariable token: String) {
        pushTokens.unregister(principal.profile.id, token.trim())
    }
}
