package com.shifa.payment.web

import com.shifa.payment.repo.DoctorSubscriptionRepository
import com.shifa.payment.repo.PaymentEventRepository
import com.shifa.payment.domain.PaymentGatewayCode
import com.shifa.payment.repo.PaymentRepository
import com.shifa.payment.repo.SubscriptionPlanRepository
import com.shifa.payment.service.StripeWebhookService
import com.shifa.security.AdminPrincipal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/payments")
class AdminPaymentController(
    private val paymentRepository: PaymentRepository,
    private val subscriptionPlanRepository: SubscriptionPlanRepository,
    private val doctorSubscriptionRepository: DoctorSubscriptionRepository,
    private val paymentEventRepository: PaymentEventRepository,
    private val stripeWebhookService: StripeWebhookService
) {
    @GetMapping("/ledger")
    fun ledger(): List<Map<String, Any?>> =
        paymentRepository.findAll().sortedByDescending { it.createdAt }.map {
            mapOf(
                "id" to it.id,
                "externalRef" to it.externalRef,
                "kind" to it.kind.name,
                "gateway" to it.gateway.name,
                "status" to it.status.name,
                "amountMinor" to it.amountMinor,
                "currency" to it.currency,
                "appointmentId" to it.appointment?.id,
                "payerUserId" to it.payerUser?.id,
                "payeeDoctorId" to it.payeeDoctor?.id,
                "createdAt" to it.createdAt.toString(),
                "paidAt" to it.paidAt?.toString()
            )
        }

    @GetMapping("/subscription/plans")
    fun subscriptionPlans(): List<Map<String, Any?>> =
        subscriptionPlanRepository.findAll().map {
            mapOf(
                "id" to it.id,
                "code" to it.code,
                "name" to it.name,
                "priceMinor" to it.priceMinor,
                "currency" to it.currency,
                "interval" to it.interval.name,
                "enabled" to it.enabled
            )
        }

    @GetMapping("/subscription/doctors")
    fun doctorSubscriptions(): List<Map<String, Any?>> =
        doctorSubscriptionRepository.findAll().sortedByDescending { it.createdAt }.map {
            mapOf(
                "id" to it.id,
                "doctorId" to it.doctor.id,
                "doctorName" to "${it.doctor.firstName} ${it.doctor.lastName}".trim(),
                "planCode" to it.plan.code,
                "status" to it.status.name,
                "gateway" to it.gateway.name,
                "periodEnd" to it.currentPeriodEnd?.toString(),
                "cancelAtPeriodEnd" to it.cancelAtPeriodEnd
            )
        }

    @GetMapping("/webhooks/stripe/failed")
    fun failedStripeWebhooks(): List<Map<String, Any?>> {
        val unprocessed = paymentEventRepository
            .findByGatewayAndProcessedFalseOrderByCreatedAtDesc(PaymentGatewayCode.STRIPE)
        val failed = paymentEventRepository
            .findByGatewayAndFailureReasonIsNotNullOrderByCreatedAtDesc(PaymentGatewayCode.STRIPE)

        return (unprocessed + failed)
            .distinctBy { it.id }
            .sortedByDescending { it.createdAt }
            .map {
                mapOf(
                    "id" to it.id,
                    "eventId" to it.eventId,
                    "eventType" to it.eventType,
                    "processed" to it.processed,
                    "processedAt" to it.processedAt?.toString(),
                    "failureReason" to it.failureReason,
                    "retryCount" to it.retryCount,
                    "lastRetryAt" to it.lastRetryAt?.toString(),
                    "retriedByAdminUserId" to it.retriedByAdminUserId,
                    "createdAt" to it.createdAt.toString()
                )
            }
    }

    @PostMapping("/webhooks/stripe/failed/{paymentEventId}/retry")
    fun retryFailedStripeWebhook(
        @AuthenticationPrincipal principal: AdminPrincipal,
        @PathVariable paymentEventId: Long
    ): Map<String, Any> {
        val ok = stripeWebhookService.retryStoredWebhookEvent(
            paymentEventId = paymentEventId,
            adminUserId = principal.adminProfile.user.id
        )
        return mapOf(
            "success" to ok,
            "paymentEventId" to paymentEventId
        )
    }
}
