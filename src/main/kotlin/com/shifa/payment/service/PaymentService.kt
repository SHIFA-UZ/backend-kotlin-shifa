package com.shifa.payment.service

import com.shifa.config.AppProperties
import com.shifa.config.ClickProperties
import com.shifa.domain.Appointment
import com.shifa.payment.domain.Payment
import com.shifa.payment.domain.PaymentGatewayCode
import com.shifa.payment.domain.PaymentKind
import com.shifa.payment.domain.PaymentStatus
import com.shifa.payment.gateway.ClickGateway
import com.shifa.payment.gateway.GatewayCheckoutRequest
import com.shifa.payment.gateway.GatewayCheckoutResult
import com.shifa.payment.gateway.ManualPaymentGateway
import com.shifa.payment.gateway.StripeGateway
import com.shifa.payment.repo.PaymentRepository
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.DoctorBillingRepository
import com.shifa.repo.PatientProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@Service
class PaymentService(
    private val paymentRepository: PaymentRepository,
    private val appointmentRepository: AppointmentRepository,
    private val doctorBillingRepository: DoctorBillingRepository,
    private val patientProfiles: PatientProfileRepository,
    private val stripeGateway: StripeGateway,
    private val clickGateway: ClickGateway,
    private val manualPaymentGateway: ManualPaymentGateway,
    private val clickProperties: ClickProperties,
    private val appProperties: AppProperties
) {
    private val log = LoggerFactory.getLogger(PaymentService::class.java)

    data class ConsultationCheckoutResult(
        val paymentId: Long,
        val externalRef: String,
        val checkoutUrl: String,
        val status: String,
        /** Gateway actually used (`CLICK` preferred with Stripe fallback when applicable). */
        val gateway: String
    )

    @Transactional
    fun createConsultationCheckout(
        userId: Long,
        appointmentId: Long,
        preferredGateway: PaymentGatewayCode?
    ): ConsultationCheckoutResult {
        val patient = patientProfiles.findByUserId(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Patient profile not found") }
        val appointment = appointmentRepository.findByIdWithDoctorAndPatient(appointmentId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found") }

        if (appointment.patient?.id != patient.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Appointment does not belong to current patient")
        }
        if (appointment.paymentStatus == Appointment.PaymentStatus.PAID) {
            val existing = paymentRepository.findByAppointment_Id(appointment.id)
            return ConsultationCheckoutResult(
                paymentId = existing?.id ?: 0,
                externalRef = existing?.externalRef ?: "already_paid",
                checkoutUrl = existing?.gatewayCheckoutUrl ?: "",
                status = PaymentStatus.PAID.name,
                gateway = existing?.gateway?.name ?: PaymentGatewayCode.STRIPE.name
            )
        }

        val amountMinor = appointment.paymentAmountMinor
            ?: appointment.doctor.consultationPriceMinor
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Doctor consultation price is not configured")
        val currency = (appointment.paymentCurrency ?: appointment.doctor.consultationCurrency ?: "UZS").uppercase()
        if (currency != "UZS") {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Online video consultation checkout is available in UZS only (payment currency=$currency)."
            )
        }

        val externalRef = UUID.randomUUID().toString().replace("-", "")
        val doctorBilling = doctorBillingRepository.findByDoctorId(appointment.doctor.id).orElse(null)
        val destinationAccountId = doctorBilling?.stripeConnectAccountId?.takeIf { it.isNotBlank() }

        if (destinationAccountId == null) {
            log.info(
                "Consultation payment {} has no connected payout account for doctor {}. Funds will settle on platform account.",
                externalRef,
                appointment.doctor.id
            )
        } else {
            log.info(
                "Consultation payment {} will route to connected account {} for doctor {}.",
                externalRef,
                destinationAccountId,
                appointment.doctor.id
            )
        }

        val gatewayCheckoutRequest =
            GatewayCheckoutRequest(
                externalRef = externalRef,
                amountMinor = amountMinor,
                currency = currency,
                kind = PaymentKind.CONSULTATION,
                description = "Consultation payment for appointment #${appointment.id}",
                destinationAccountId = destinationAccountId,
                successUrl =
                "${appProperties.publicBaseUrl.removeSuffix("/")}/api/payments/checkout/success?ref=$externalRef",
                cancelUrl =
                "${appProperties.publicBaseUrl.removeSuffix("/")}/api/payments/checkout/cancel?ref=$externalRef"
            )

        val (usedGateway: PaymentGatewayCode, gatewayResult: GatewayCheckoutResult) =
            resolveConsultationCheckoutGateway(preferredGateway, gatewayCheckoutRequest)

        val payment = paymentRepository.save(
            Payment(
                externalRef = externalRef,
                gateway = usedGateway,
                kind = PaymentKind.CONSULTATION,
                status = PaymentStatus.PENDING,
                amountMinor = amountMinor,
                currency = currency,
                appointment = appointment,
                payerUser = patient.user,
                payeeDoctor = appointment.doctor,
                gatewayPaymentId = gatewayResult.gatewayPaymentId,
                gatewayCheckoutUrl = gatewayResult.checkoutUrl
            )
        )

        appointment.paymentAmountMinor = amountMinor
        appointment.paymentCurrency = currency
        appointment.paymentStatus = Appointment.PaymentStatus.PENDING
        appointmentRepository.save(appointment)

        return ConsultationCheckoutResult(
            paymentId = payment.id,
            externalRef = payment.externalRef,
            checkoutUrl = gatewayResult.checkoutUrl,
            status = payment.status.name,
            gateway = usedGateway.name
        )
    }

    private fun resolveConsultationCheckoutGateway(
        preferred: PaymentGatewayCode?,
        req: GatewayCheckoutRequest
    ): Pair<PaymentGatewayCode, GatewayCheckoutResult> {
        fun stripe(): Pair<PaymentGatewayCode, GatewayCheckoutResult> =
            PaymentGatewayCode.STRIPE to stripeGateway.createCheckout(req)

        return when (preferred) {
            PaymentGatewayCode.STRIPE,
            PaymentGatewayCode.PAYME -> stripe()

            PaymentGatewayCode.MANUAL ->
                PaymentGatewayCode.MANUAL to manualPaymentGateway.createCheckout(req)

            PaymentGatewayCode.CLICK,
            null -> tryClickThenStripeFallback(req)
        }
    }

    private fun tryClickThenStripeFallback(
        req: GatewayCheckoutRequest
    ): Pair<PaymentGatewayCode, GatewayCheckoutResult> {
        if (
            preferredClickFirst() &&
            clickProperties.secretKey.isNotBlank() &&
            clickProperties.merchantId > 0L &&
            clickProperties.serviceId > 0L
        ) {
            try {
                return PaymentGatewayCode.CLICK to clickGateway.createCheckout(req)
            } catch (e: Exception) {
                log.warn("Click checkout failed for ref={}, falling back to Stripe: {}", req.externalRef, e.message)
            }
        }
        return PaymentGatewayCode.STRIPE to stripeGateway.createCheckout(req)
    }

    private fun preferredClickFirst(): Boolean {
        if (!clickProperties.enabled) return false
        return true
    }

    @Transactional(readOnly = true)
    fun getPaymentStatus(paymentId: Long, userId: Long): Payment {
        val payment = paymentRepository.findById(paymentId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found") }
        if (payment.payerUser?.id != userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Payment does not belong to current user")
        }
        return payment
    }

    @Transactional
    fun markPaymentPaidByExternalRef(externalRef: String): Payment {
        val payment = paymentRepository.findByExternalRef(externalRef)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found")
        if (payment.status == PaymentStatus.PAID) return payment
        payment.status = PaymentStatus.PAID
        payment.paidAt = Instant.now()
        payment.updatedAt = Instant.now()
        payment.appointment?.let {
            it.paymentStatus = Appointment.PaymentStatus.PAID
            when (it.status) {
                Appointment.Status.IN_PROGRESS,
                Appointment.Status.COMPLETED,
                Appointment.Status.CANCELLED -> Unit

                else -> {
                    it.status = Appointment.Status.CONFIRMED
                }
            }
            appointmentRepository.save(it)
        }
        return paymentRepository.save(payment)
    }

    @Transactional
    fun markConsultationPaymentCancelledFromClick(merchantTransId: String, detail: String) {
        val payment = paymentRepository.findByExternalRef(merchantTransId) ?: return
        if (payment.kind != PaymentKind.CONSULTATION || payment.gateway != PaymentGatewayCode.CLICK) return
        if (payment.status == PaymentStatus.PAID) return
        payment.status = PaymentStatus.CANCELLED
        payment.failureReason = detail
        payment.updatedAt = Instant.now()
        paymentRepository.save(payment)
        payment.appointment?.let { apt ->
            apt.paymentStatus = Appointment.PaymentStatus.FAILED
            appointmentRepository.save(apt)
        }
    }

    @Transactional
    fun markPaymentPaidByGatewayPaymentId(gatewayPaymentId: String): Payment? {
        val payment = paymentRepository.findByGatewayPaymentId(gatewayPaymentId) ?: return null
        if (payment.status == PaymentStatus.PAID) return payment
        payment.status = PaymentStatus.PAID
        payment.paidAt = Instant.now()
        payment.updatedAt = Instant.now()
        payment.appointment?.let {
            it.paymentStatus = Appointment.PaymentStatus.PAID
            when (it.status) {
                Appointment.Status.IN_PROGRESS,
                Appointment.Status.COMPLETED,
                Appointment.Status.CANCELLED -> Unit

                else -> {
                    it.status = Appointment.Status.CONFIRMED
                }
            }
            appointmentRepository.save(it)
        }
        return paymentRepository.save(payment)
    }
}
