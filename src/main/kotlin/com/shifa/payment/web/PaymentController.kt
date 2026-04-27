package com.shifa.payment.web

import com.shifa.payment.domain.PaymentGatewayCode
import com.shifa.payment.service.PaymentService
import com.shifa.security.PatientPrincipal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/payments")
class PaymentController(
    private val paymentService: PaymentService
) {
    data class ConsultationCheckoutRequest(
        val appointmentId: Long,
        val gateway: PaymentGatewayCode? = null
    )

    data class PaymentStatusDto(
        val id: Long,
        val status: String,
        val gateway: String,
        val amountMinor: Long,
        val currency: String,
        val checkoutUrl: String?
    )

    @PostMapping("/consultation/checkout")
    fun createConsultationCheckout(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @RequestBody body: ConsultationCheckoutRequest
    ) = paymentService.createConsultationCheckout(
        userId = principal.user.id!!,
        appointmentId = body.appointmentId,
        preferredGateway = body.gateway
    )

    @GetMapping("/{paymentId}")
    fun getPaymentStatus(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @PathVariable paymentId: Long
    ): PaymentStatusDto {
        val payment = paymentService.getPaymentStatus(paymentId, principal.user.id!!)
        return PaymentStatusDto(
            id = payment.id,
            status = payment.status.name,
            gateway = payment.gateway.name,
            amountMinor = payment.amountMinor,
            currency = payment.currency,
            checkoutUrl = payment.gatewayCheckoutUrl
        )
    }
}
