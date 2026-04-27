package com.shifa.payment.service

import com.shifa.config.AppProperties
import com.shifa.config.StripeProperties
import com.shifa.payment.domain.DoctorSubscription
import com.shifa.payment.domain.DoctorSubscriptionStatus
import com.shifa.payment.domain.PaymentGatewayCode
import com.shifa.payment.repo.DoctorSubscriptionRepository
import com.shifa.payment.repo.SubscriptionPlanRepository
import com.stripe.Stripe
import com.stripe.model.Subscription
import com.stripe.model.checkout.Session
import com.stripe.param.checkout.SessionCreateParams
import com.shifa.repo.DoctorProfileRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@Service
class SubscriptionService(
    private val doctorProfiles: DoctorProfileRepository,
    private val planRepository: SubscriptionPlanRepository,
    private val doctorSubscriptionRepository: DoctorSubscriptionRepository,
    private val stripeProperties: StripeProperties,
    private val appProperties: AppProperties
) {
    companion object {
        private const val DEFAULT_GRACE_DAYS: Long = 7
    }
    data class SubscriptionCheckoutResult(
        val checkoutUrl: String,
        val sessionId: String
    )

    @Transactional(readOnly = true)
    fun listActivePlans() = planRepository.findByEnabledTrueOrderByPriceMinorAsc()

    @Transactional(readOnly = true)
    fun getDoctorSubscription(doctorId: Long): DoctorSubscription? =
        doctorSubscriptionRepository.findTopByDoctor_IdOrderByCreatedAtDesc(doctorId)

    @Transactional
    fun startSubscription(doctorId: Long, planCode: String, gateway: PaymentGatewayCode): DoctorSubscription {
        val doctor = doctorProfiles.findById(doctorId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found") }
        val plan = planRepository.findByCode(planCode)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found: $planCode")

        val now = Instant.now()
        val current = doctorSubscriptionRepository.findTopByDoctor_IdOrderByCreatedAtDesc(doctorId)
        if (current != null && current.status == DoctorSubscriptionStatus.ACTIVE) {
            return current
        }

        return doctorSubscriptionRepository.save(
            DoctorSubscription(
                doctor = doctor,
                plan = plan,
                gateway = gateway,
                status = DoctorSubscriptionStatus.ACTIVE,
                trialEndsAt = null,
                currentPeriodEnd = now.plusSeconds(30L * 24L * 60L * 60L)
            )
        )
    }

    @Transactional
    fun createStripeCheckout(doctorId: Long, planCode: String): SubscriptionCheckoutResult {
        val doctor = doctorProfiles.findById(doctorId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found") }
        val plan = planRepository.findByCode(planCode)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found: $planCode")

        val apiKey = stripeProperties.secretKey.trim()
        if (apiKey.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Stripe is not configured")
        }
        Stripe.apiKey = apiKey

        val recurringInterval = when (plan.interval.name) {
            "YEAR" -> SessionCreateParams.LineItem.PriceData.Recurring.Interval.YEAR
            else -> SessionCreateParams.LineItem.PriceData.Recurring.Interval.MONTH
        }

        val lineItem = SessionCreateParams.LineItem.builder()
            .setQuantity(1)
            .setPriceData(
                SessionCreateParams.LineItem.PriceData.builder()
                    .setCurrency(plan.currency.lowercase())
                    .setUnitAmount(plan.priceMinor)
                    .setRecurring(
                        SessionCreateParams.LineItem.PriceData.Recurring.builder()
                            .setInterval(recurringInterval)
                            .build()
                    )
                    .setProductData(
                        SessionCreateParams.LineItem.PriceData.ProductData.builder()
                            .setName(plan.name)
                            .build()
                    )
                    .build()
            )
            .build()

        val session = Session.create(
            SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setClientReferenceId("doctor_${doctor.id}_${plan.code}")
                .putMetadata("doctorId", doctor.id.toString())
                .putMetadata("planCode", plan.code)
                .setSuccessUrl("${appProperties.publicBaseUrl.removeSuffix("/")}/doctor/subscription/success")
                .setCancelUrl("${appProperties.publicBaseUrl.removeSuffix("/")}/doctor/subscription/cancel")
                .addLineItem(lineItem)
                .build()
        )

        return SubscriptionCheckoutResult(
            checkoutUrl = session.url ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Stripe checkout URL missing"),
            sessionId = session.id
        )
    }

    @Transactional
    fun activateByStripeCheckoutSession(session: Session) {
        val doctorId = session.metadata?.get("doctorId")?.toLongOrNull() ?: return
        val planCode = session.metadata?.get("planCode") ?: return
        val plan = planRepository.findByCode(planCode) ?: return
        val doctor = doctorProfiles.findById(doctorId).orElse(null) ?: return

        val existing = doctorSubscriptionRepository.findTopByDoctor_IdOrderByCreatedAtDesc(doctorId)
        val subscription = if (existing != null) {
            existing.plan = plan
            existing.gateway = PaymentGatewayCode.STRIPE
            existing.externalSubscriptionId = session.subscription
            existing.status = DoctorSubscriptionStatus.ACTIVE
            existing.currentPeriodEnd = Instant.now().plusSeconds(30L * 24L * 60L * 60L)
            existing.gracePeriodEndsAt = null
            existing.suspendedAt = null
            existing.updatedAt = Instant.now()
            existing
        } else {
            DoctorSubscription(
                doctor = doctor,
                plan = plan,
                gateway = PaymentGatewayCode.STRIPE,
                externalSubscriptionId = session.subscription,
                status = DoctorSubscriptionStatus.ACTIVE,
                currentPeriodEnd = Instant.now().plusSeconds(30L * 24L * 60L * 60L),
                gracePeriodEndsAt = null,
                suspendedAt = null
            )
        }
        doctorSubscriptionRepository.save(subscription)
    }

    @Transactional
    fun markPaidByStripeSubscriptionId(extSubId: String) {
        val sub = doctorSubscriptionRepository.findTopByExternalSubscriptionIdOrderByCreatedAtDesc(extSubId) ?: return
        sub.status = DoctorSubscriptionStatus.ACTIVE
        sub.gracePeriodEndsAt = null
        sub.suspendedAt = null
        sub.updatedAt = Instant.now()
        doctorSubscriptionRepository.save(sub)
    }

    @Transactional
    fun markPaymentFailedByStripeSubscriptionId(extSubId: String) {
        val sub = doctorSubscriptionRepository.findTopByExternalSubscriptionIdOrderByCreatedAtDesc(extSubId) ?: return
        sub.status = DoctorSubscriptionStatus.PAST_DUE
        sub.gracePeriodEndsAt = Instant.now().plusSeconds(DEFAULT_GRACE_DAYS * 24L * 60L * 60L)
        sub.updatedAt = Instant.now()
        doctorSubscriptionRepository.save(sub)
    }

    @Transactional
    fun markCancelledByStripeSubscription(stripeSubscription: Subscription) {
        val extSubId = stripeSubscription.id ?: return
        val sub = doctorSubscriptionRepository.findTopByExternalSubscriptionIdOrderByCreatedAtDesc(extSubId) ?: return
        sub.status = DoctorSubscriptionStatus.CANCELLED
        sub.suspendedAt = Instant.now()
        sub.updatedAt = Instant.now()
        doctorSubscriptionRepository.save(sub)
    }
}
