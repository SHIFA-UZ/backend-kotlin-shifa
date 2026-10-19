package com.shifa.payment.web

import com.shifa.payment.domain.PaymentGatewayCode
import com.shifa.payment.service.PaymentService
import com.shifa.security.PatientPrincipal
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/payments")
class PaymentController(
    private val paymentService: PaymentService
) {
    data class ConsultationCheckoutRequest(
        val appointmentId: Long,
        val gateway: PaymentGatewayCode? = null
    )

    data class TreatmentPlanCheckoutRequest(
        val treatmentPlanId: Long,
        val installmentItemId: Long? = null,
        val gateway: PaymentGatewayCode? = null,
    )

    data class PaymentStatusDto(
        val id: Long,
        val status: String,
        val gateway: String,
        val amountMinor: Long,
        val currency: String,
        val checkoutUrl: String?
    )

    @GetMapping("/checkout/success", produces = [MediaType.TEXT_HTML_VALUE])
    fun checkoutSuccessHtml(
        @RequestParam(name = "ref", required = false) ref: String?
    ): String =
        htmlPaymentPage(
            title = "Payment successful",
            message = if (!ref.isNullOrBlank()) {
                "Your payment reference: $ref. You may return to the Shifa app — your booking will update once payment is confirmed."
            } else {
                "You may return to the Shifa app — your booking will update once payment is confirmed."
            }
        )

    @GetMapping("/checkout/cancel", produces = [MediaType.TEXT_HTML_VALUE])
    fun checkoutCancelHtml(
        @RequestParam(name = "ref", required = false) ref: String?
    ): String =
        htmlPaymentPage(
            title = "Payment cancelled",
            message = if (!ref.isNullOrBlank()) {
                "Reference: $ref. You can retry payment from your appointments screen in the app."
            } else {
                "You can retry payment from your appointments screen in the app."
            }
        )

    private fun htmlPaymentPage(title: String, message: String): String =
        """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8"/>
  <meta name="viewport" content="width=device-width,initial-scale=1"/>
  <title>$title — Shifa</title>
</head>
<body style="font-family:system-ui,Segoe UI,sans-serif;padding:24px;max-width:520px;line-height:1.5;">
  <h2 style="margin-top:0;">$title</h2>
  <p>$message</p>
</body>
</html>""".trimIndent()

    @Operation(
        summary = "Enabled online payment methods for a service",
        description = "One item is the single-method sheet. Two items are the choice sheet. Empty when the service is free or pay-at-clinic. Amounts are minor units; UZS has no subunit, so 300000 is 300000.00 UZS.",
    )
    @GetMapping("/methods")
    fun methods(
        @RequestParam serviceId: Long,
        @RequestParam(required = false) locationId: Long?,
    ): List<PaymentService.PaymentMethodQuote> =
        paymentService.listConsultationMethods(serviceId, locationId)

    @PostMapping("/consultation/checkout")
    fun createConsultationCheckout(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @RequestBody body: ConsultationCheckoutRequest
    ) = paymentService.createConsultationCheckout(
        userId = principal.user.id!!,
        appointmentId = body.appointmentId,
        preferredGateway = body.gateway
    )

    @PostMapping("/treatment-plan/checkout")
    fun createTreatmentPlanCheckout(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @RequestBody body: TreatmentPlanCheckoutRequest
    ) = paymentService.createTreatmentPlanCheckout(
        userId = principal.user.id!!,
        treatmentPlanId = body.treatmentPlanId,
        installmentItemId = body.installmentItemId,
        preferredGateway = body.gateway,
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
