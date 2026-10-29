package com.shifa.payments.application

import com.shifa.payments.domain.DoctorSubscription
import com.shifa.payments.domain.Payment
import com.shifa.payments.domain.PaymentEvent
import com.shifa.payments.domain.PaymentGatewayCode
import com.shifa.payments.domain.SubscriptionPlan
import com.shifa.payments.infrastructure.DoctorSubscriptionRepository
import com.shifa.payments.infrastructure.PaymentEventRepository
import com.shifa.payments.infrastructure.PaymentRepository
import com.shifa.payments.infrastructure.SubscriptionPlanRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Read models for the admin panel's payment pages; the controller only shapes them. */
@Service
class PaymentAdminQueries(
    private val payments: PaymentRepository,
    private val plans: SubscriptionPlanRepository,
    private val doctorSubscriptions: DoctorSubscriptionRepository,
    private val paymentEvents: PaymentEventRepository,
) {
    @Transactional(readOnly = true)
    fun ledger(): List<Payment> = payments.findAll().sortedByDescending { it.createdAt }

    @Transactional(readOnly = true)
    fun subscriptionPlans(): List<SubscriptionPlan> = plans.findAll()

    @Transactional(readOnly = true)
    fun doctorSubscriptions(): List<DoctorSubscription> = doctorSubscriptions.findAll().sortedByDescending { it.createdAt }

    /** Stripe events that were not processed or recorded a failure, newest first, each once. */
    @Transactional(readOnly = true)
    fun failedStripeWebhooks(): List<PaymentEvent> {
        val unprocessed = paymentEvents.findByGatewayAndProcessedFalseOrderByCreatedAtDesc(PaymentGatewayCode.STRIPE)
        val failed = paymentEvents.findByGatewayAndFailureReasonIsNotNullOrderByCreatedAtDesc(PaymentGatewayCode.STRIPE)
        return (unprocessed + failed).distinctBy { it.id }.sortedByDescending { it.createdAt }
    }
}
