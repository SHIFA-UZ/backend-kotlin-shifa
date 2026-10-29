package com.shifa.payments.api

import com.shifa.payments.api.dto.StartSubscriptionRequest
import com.shifa.payments.api.dto.SubscriptionDto
import com.shifa.payments.domain.PaymentGatewayCode
import com.shifa.payments.application.SubscriptionService
import com.shifa.shared.security.DoctorPrincipal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/doctor/subscription")
class SubscriptionController(
    private val subscriptionService: SubscriptionService
) {


    @GetMapping("/plans")
    fun listPlans(): List<Map<String, Any?>> =
        subscriptionService.listActivePlans().map {
            mapOf(
                "code" to it.code,
                "name" to it.name,
                "priceMinor" to it.priceMinor,
                "currency" to it.currency,
                "interval" to it.interval.name,
                "featuresJson" to it.featuresJson
            )
        }

    @GetMapping("/me")
    fun getMySubscription(
        @AuthenticationPrincipal principal: DoctorPrincipal
    ): SubscriptionDto? {
        val doctorId = principal.profile.id ?: return null
        val subscription = subscriptionService.getDoctorSubscription(doctorId) ?: return null
        return SubscriptionDto(
            id = subscription.id,
            planCode = subscription.plan.code,
            status = subscription.status.name,
            gateway = subscription.gateway.name,
            currentPeriodEnd = subscription.currentPeriodEnd?.toString(),
            gracePeriodEndsAt = subscription.gracePeriodEndsAt?.toString(),
            suspendedAt = subscription.suspendedAt?.toString()
        )
    }

    @PostMapping("/start")
    fun startSubscription(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody body: StartSubscriptionRequest
    ): SubscriptionDto {
        val doctorId = principal.profile.id!!
        val subscription = subscriptionService.startSubscription(doctorId, body.planCode, body.gateway)
        return SubscriptionDto(
            id = subscription.id,
            planCode = subscription.plan.code,
            status = subscription.status.name,
            gateway = subscription.gateway.name,
            currentPeriodEnd = subscription.currentPeriodEnd?.toString(),
            gracePeriodEndsAt = subscription.gracePeriodEndsAt?.toString(),
            suspendedAt = subscription.suspendedAt?.toString()
        )
    }

    @PostMapping("/checkout")
    fun createCheckout(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody body: StartSubscriptionRequest
    ): Map<String, String> {
        val doctorId = principal.profile.id!!
        val checkout = subscriptionService.createStripeCheckout(doctorId, body.planCode)
        return mapOf(
            "checkoutUrl" to checkout.checkoutUrl,
            "sessionId" to checkout.sessionId
        )
    }
}
