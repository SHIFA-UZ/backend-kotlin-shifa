package com.shifa.payments.api

import com.shifa.payments.api.dto.OnboardRequest
import com.shifa.payments.application.StripeConnectService
import com.shifa.shared.security.DoctorPrincipal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/doctor/payments/stripe/connect")
class DoctorStripeConnectController(
    private val stripeConnectService: StripeConnectService
) {

    @PostMapping("/onboard")
    fun onboard(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody(required = false) body: OnboardRequest?
    ): Map<String, String> {
        val doctorId = principal.profile.id!!
        val result = stripeConnectService.createOrResumeOnboarding(
            doctorId = doctorId,
            refreshUrl = body?.refreshUrl,
            returnUrl = body?.returnUrl
        )
        return mapOf(
            "accountId" to result.accountId,
            "onboardingUrl" to result.onboardingUrl
        )
    }
}
