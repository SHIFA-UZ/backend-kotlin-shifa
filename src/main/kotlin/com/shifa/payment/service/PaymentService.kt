package com.shifa.payment.service

import com.shifa.config.AppProperties
import com.shifa.config.ClickProperties
import com.shifa.config.PaymentProperties
import com.shifa.config.StripeProperties
import com.shifa.domain.Appointment
import com.shifa.domain.InstallmentItem
import com.shifa.domain.Notification
import com.shifa.domain.TreatmentPlanPayment
import com.shifa.repo.DoctorServicePriceRepository
import com.shifa.repo.DoctorServiceRepository
import com.shifa.service.AppointmentPaymentSettlement
import com.shifa.service.BookingPaymentPolicy
import com.shifa.service.DoctorServicePricing
import com.shifa.payment.domain.OpenCheckoutPolicy
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
import com.shifa.repo.InstallmentItemRepository
import com.shifa.repo.NotificationRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.repo.TreatmentPlanRepository
import com.shifa.service.FcmService
import com.shifa.service.InstallmentService
import com.shifa.service.TreatmentPlanFinanceService
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
    private val treatmentPlans: TreatmentPlanRepository,
    private val installmentItems: InstallmentItemRepository,
    private val financeService: TreatmentPlanFinanceService,
    private val installmentService: InstallmentService,
    private val notificationRepository: NotificationRepository,
    private val fcmService: FcmService,
    private val stripeGateway: StripeGateway,
    private val clickGateway: ClickGateway,
    private val manualPaymentGateway: ManualPaymentGateway,
    private val clickProperties: ClickProperties,
    private val appProperties: AppProperties,
    private val paymentProperties: PaymentProperties,
    private val stripeProperties: StripeProperties,
    private val doctorServices: DoctorServiceRepository,
    private val doctorServicePrices: DoctorServicePriceRepository,
    private val settlement: AppointmentPaymentSettlement,
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

    data class TreatmentPlanCheckoutResult(
        val paymentId: Long,
        val externalRef: String,
        val checkoutUrl: String,
        val status: String,
        val gateway: String,
        val amountMinor: Long,
        val currency: String,
    )

    /** [returnPlatform] is "app" when the patient app started the checkout: the return pages then redirect into the app. */
    @Transactional
    fun createConsultationCheckout(
        userId: Long,
        appointmentId: Long,
        preferredGateway: PaymentGatewayCode?,
        returnPlatform: String? = null,
    ): ConsultationCheckoutResult {
        val patient = patientProfiles.findByUserId(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Patient profile not found") }
        // Serialise checkouts per appointment: a double tap or two devices then see one open checkout, not two.
        appointmentRepository.findByIdForUpdate(appointmentId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found") }
        val appointment = appointmentRepository.findByIdWithDoctorAndPatient(appointmentId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found") }

        if (appointment.patient?.id != patient.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Appointment does not belong to current patient")
        }
        if (appointment.status == Appointment.Status.EXPIRED ||
            appointment.status == Appointment.Status.PAYMENT_FAILED ||
            appointment.status == Appointment.Status.CANCELLED
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "This appointment is no longer payable")
        }
        if (appointment.paymentStatus == Appointment.PaymentStatus.NOT_REQUIRED ||
            appointment.paymentStatus == Appointment.PaymentStatus.PAY_IN_CLINIC
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "This appointment does not require online payment")
        }
        if (appointment.paymentStatus == Appointment.PaymentStatus.PAID) {
            val existing = paymentRepository.findFirstByAppointment_IdAndStatusOrderByCreatedAtDesc(appointment.id, PaymentStatus.PAID)
            return ConsultationCheckoutResult(
                paymentId = existing?.id ?: 0,
                externalRef = existing?.externalRef ?: "already_paid",
                checkoutUrl = existing?.gatewayCheckoutUrl ?: "",
                status = PaymentStatus.PAID.name,
                gateway = existing?.gateway?.name ?: PaymentGatewayCode.STRIPE.name
            )
        }

        // Idempotency: a second call (double tap, retry) hands back the open checkout instead of opening another.
        val openCheckouts = paymentRepository.findByAppointment_IdAndStatusInOrderByCreatedAtDesc(
            appointment.id, OpenCheckoutPolicy.OPEN_STATUSES
        )
        val now = Instant.now()
        openCheckouts.firstOrNull { OpenCheckoutPolicy.reusable(it, preferredGateway, now, paymentProperties.holdMinutes) }
            ?.let { existing ->
                log.info("Reusing open checkout {} for appointment {}", existing.externalRef, appointment.id)
                return ConsultationCheckoutResult(
                    paymentId = existing.id,
                    externalRef = existing.externalRef,
                    checkoutUrl = existing.gatewayCheckoutUrl ?: "",
                    status = existing.status.name,
                    gateway = existing.gateway.name
                )
            }
        // Stale or other-gateway checkouts must close first: V118 allows one open row per appointment.
        if (openCheckouts.isNotEmpty()) {
            openCheckouts.forEach { stale ->
                stale.status = PaymentStatus.CANCELLED
                stale.failureReason = "Superseded by a new checkout"
                stale.updatedAt = now
            }
            // Hibernate runs INSERTs before UPDATEs at flush time; push the cancellations down first.
            paymentRepository.saveAllAndFlush(openCheckouts)
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
                "${appProperties.publicBaseUrl.removeSuffix("/")}/api/payments/checkout/success?ref=$externalRef${returnPlatformQuery(returnPlatform)}",
                cancelUrl =
                "${appProperties.publicBaseUrl.removeSuffix("/")}/api/payments/checkout/cancel?ref=$externalRef${returnPlatformQuery(returnPlatform)}"
            )

        val (usedGateway: PaymentGatewayCode, gatewayResult: GatewayCheckoutResult) =
            resolveConsultationCheckoutGateway(preferredGateway, gatewayCheckoutRequest, appointment.id)

        log.info(
            "Consultation checkout using gateway={} appointmentId={} amountMinor={} currency={} externalRef={} preferredGateway={}",
            usedGateway.name,
            appointment.id,
            amountMinor,
            currency,
            externalRef,
            preferredGateway?.name ?: "AUTO"
        )

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

    @Transactional
    fun createTreatmentPlanCheckout(
        userId: Long,
        treatmentPlanId: Long,
        installmentItemId: Long?,
        preferredGateway: PaymentGatewayCode? = null,
        returnPlatform: String? = null,
    ): TreatmentPlanCheckoutResult {
        val patient = patientProfiles.findByUserId(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Patient profile not found") }
        val plan = treatmentPlans.findById(treatmentPlanId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Treatment plan not found")
        }
        if (plan.patient.id != patient.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Treatment plan not found")
        }

        val totals = financeService.planLiveTotals(plan.id)
        val currency = totals.currency.uppercase()
        if (currency != "UZS") {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Online bill payment is available in UZS only (currency=$currency).",
            )
        }
        if (totals.remainingMinor <= 0L) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing owed on this treatment plan")
        }

        val usedGateway = when (preferredGateway) {
            PaymentGatewayCode.STRIPE,
            PaymentGatewayCode.PAYME -> PaymentGatewayCode.STRIPE
            else -> PaymentGatewayCode.CLICK
        }
        if (usedGateway == PaymentGatewayCode.CLICK) {
            val skip = clickSkipReason()
            if (skip != null) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Click payments unavailable ($skip)")
            }
        }

        var installment: InstallmentItem? = null
        val amountMinor: Long
        if (installmentItemId != null) {
            installment = installmentItems.findById(installmentItemId).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Installment item not found")
            }
            if (installment.installmentPlan.treatmentPlan.id != plan.id) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Installment does not belong to this plan")
            }
            when (installment.status) {
                InstallmentItem.Status.PAID,
                InstallmentItem.Status.CANCELLED,
                InstallmentItem.Status.WAIVED ->
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Installment cannot be paid (status=${installment.status})",
                    )
                InstallmentItem.Status.PENDING,
                InstallmentItem.Status.OVERDUE -> Unit
            }
            val itemCurrency = installment.currency.uppercase()
            if (itemCurrency != "UZS") {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Online bill payment is available in UZS only (currency=$itemCurrency).",
                )
            }
            amountMinor = minOf(installment.amountMinor, totals.remainingMinor).coerceAtLeast(0)
        } else {
            amountMinor = totals.remainingMinor
        }
        if (amountMinor <= 0L) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment amount must be positive")
        }

        val existingPending = paymentRepository.findPendingTreatmentPlanCheckout(
            kind = PaymentKind.TREATMENT_PLAN,
            gateway = usedGateway,
            status = PaymentStatus.PENDING,
            planId = plan.id,
            amountMinor = amountMinor,
            installmentItemId = installment?.id,
        ).firstOrNull()
        if (existingPending != null && !existingPending.gatewayCheckoutUrl.isNullOrBlank()) {
            return TreatmentPlanCheckoutResult(
                paymentId = existingPending.id,
                externalRef = existingPending.externalRef,
                checkoutUrl = existingPending.gatewayCheckoutUrl!!,
                status = existingPending.status.name,
                gateway = existingPending.gateway.name,
                amountMinor = existingPending.amountMinor,
                currency = existingPending.currency,
            )
        }

        val externalRef = UUID.randomUUID().toString().replace("-", "")
        val payee = plan.attendingDoctor
        val destinationAccountId = if (usedGateway == PaymentGatewayCode.STRIPE) {
            payee?.let {
                doctorBillingRepository.findByDoctorId(it.id).orElse(null)
                    ?.stripeConnectAccountId?.takeIf { id -> id.isNotBlank() }
            }
        } else {
            null
        }
        val gatewayCheckoutRequest = GatewayCheckoutRequest(
            externalRef = externalRef,
            amountMinor = amountMinor,
            currency = currency,
            kind = PaymentKind.TREATMENT_PLAN,
            description = if (installment != null) {
                "Treatment plan #${plan.id} installment #${installment.sequenceNumber}"
            } else {
                "Treatment plan #${plan.id} outstanding balance"
            },
            destinationAccountId = destinationAccountId,
            successUrl =
            "${appProperties.publicBaseUrl.removeSuffix("/")}/api/payments/checkout/success?ref=$externalRef${returnPlatformQuery(returnPlatform)}",
            cancelUrl =
            "${appProperties.publicBaseUrl.removeSuffix("/")}/api/payments/checkout/cancel?ref=$externalRef${returnPlatformQuery(returnPlatform)}",
        )
        val gatewayResult = try {
            when (usedGateway) {
                PaymentGatewayCode.STRIPE -> stripeGateway.createCheckout(gatewayCheckoutRequest)
                else -> clickGateway.createCheckout(gatewayCheckoutRequest)
            }
        } catch (e: Exception) {
            log.warn(
                "Treatment plan checkout failed gateway={} planId={}: {}",
                usedGateway.name,
                plan.id,
                e.message,
                e,
            )
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Could not start ${usedGateway.name} checkout: ${e.message}",
            )
        }

        val payment = paymentRepository.save(
            Payment(
                externalRef = externalRef,
                gateway = usedGateway,
                kind = PaymentKind.TREATMENT_PLAN,
                status = PaymentStatus.PENDING,
                amountMinor = amountMinor,
                currency = currency,
                treatmentPlan = plan,
                installmentItem = installment,
                payerUser = patient.user,
                payeeDoctor = payee,
                gatewayPaymentId = gatewayResult.gatewayPaymentId,
                gatewayCheckoutUrl = gatewayResult.checkoutUrl,
            )
        )

        log.info(
            "Treatment plan checkout gateway={} planId={} installmentItemId={} amountMinor={} externalRef={}",
            usedGateway.name,
            plan.id,
            installment?.id,
            amountMinor,
            externalRef,
        )

        return TreatmentPlanCheckoutResult(
            paymentId = payment.id,
            externalRef = payment.externalRef,
            checkoutUrl = gatewayResult.checkoutUrl,
            status = payment.status.name,
            gateway = usedGateway.name,
            amountMinor = payment.amountMinor,
            currency = payment.currency,
        )
    }

    /**
     * True when remaining plan balance still covers [payment.amountMinor]
     * (desk payment may have reduced the balance after checkout was created).
     */
    fun treatmentPlanAmountStillPayable(payment: Payment): Boolean {
        val planId = payment.treatmentPlan?.id ?: return false
        val remaining = financeService.planLiveTotals(planId).remainingMinor
        return remaining >= payment.amountMinor
    }

    /**
     * After Click complete: record clinic ledger payment and mark installment(s).
     * Idempotent via [TreatmentPlanPayment.onlinePaymentId].
     */
    @Transactional
    fun applyTreatmentPlanPaymentFromGateway(payment: Payment) {
        if (payment.kind != PaymentKind.TREATMENT_PLAN) return
        if (payment.status != PaymentStatus.PAID) return
        val plan = payment.treatmentPlan ?: return

        val ledgerMethod = if (payment.gateway == PaymentGatewayCode.STRIPE) {
            TreatmentPlanPayment.PaymentMethod.STRIPE
        } else {
            TreatmentPlanPayment.PaymentMethod.CLICK
        }
        val ledger = financeService.recordPaymentAndSync(
            planId = plan.id,
            amountMinor = payment.amountMinor,
            currency = payment.currency,
            method = ledgerMethod,
            memo = "${payment.gateway.name} ${payment.externalRef}",
            recordedByUser = payment.payerUser,
            financialRecord = null,
            linkedAppointment = null,
            onlinePaymentId = payment.id,
        )

        val selectedInstallment = payment.installmentItem
        if (selectedInstallment != null) {
            if (selectedInstallment.status != InstallmentItem.Status.PAID) {
                installmentService.markInstallmentPaid(selectedInstallment.id, ledger)
            }
        } else {
            // Full outstanding: allocate FIFO across unpaid installments.
            var remaining = payment.amountMinor
            val unpaid = installmentItems.findUnpaidByTreatmentPlan(
                plan.id,
                listOf(InstallmentItem.Status.PENDING, InstallmentItem.Status.OVERDUE),
            )
            for (item in unpaid) {
                if (remaining <= 0L) break
                if (item.amountMinor <= remaining) {
                    installmentService.markInstallmentPaid(item.id, ledger)
                    remaining -= item.amountMinor
                } else {
                    break
                }
            }
        }

        notifyDoctorTreatmentPlanPaid(payment)
    }

    private fun resolveConsultationCheckoutGateway(
        preferred: PaymentGatewayCode?,
        req: GatewayCheckoutRequest,
        appointmentId: Long
    ): Pair<PaymentGatewayCode, GatewayCheckoutResult> {
        fun stripe(): Pair<PaymentGatewayCode, GatewayCheckoutResult> =
            PaymentGatewayCode.STRIPE to stripeGateway.createCheckout(req)

        return when (preferred) {
            PaymentGatewayCode.STRIPE,
            PaymentGatewayCode.PAYME -> {
                log.info(
                    "Consultation checkout gateway=STRIPE (explicit preferred={}) appointmentId={} externalRef={}",
                    preferred?.name,
                    appointmentId,
                    req.externalRef
                )
                stripe()
            }

            PaymentGatewayCode.MANUAL ->
                PaymentGatewayCode.MANUAL to manualPaymentGateway.createCheckout(req)

            PaymentGatewayCode.CLICK -> {
                val skipReason = clickSkipReason()
                if (skipReason != null) {
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Click payments unavailable ($skipReason)",
                    )
                }
                try {
                    PaymentGatewayCode.CLICK to clickGateway.createCheckout(req)
                } catch (e: Exception) {
                    log.warn(
                        "Consultation checkout Click failed (explicit) appointmentId={} externalRef={}: {}",
                        appointmentId,
                        req.externalRef,
                        e.message,
                    )
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Could not start Click checkout: ${e.message}",
                    )
                }
            }

            null -> tryClickThenStripeFallback(req, appointmentId)
        }
    }

    private fun tryClickThenStripeFallback(
        req: GatewayCheckoutRequest,
        appointmentId: Long
    ): Pair<PaymentGatewayCode, GatewayCheckoutResult> {
        val skipReason = clickSkipReason()
        if (skipReason != null) {
            log.info(
                "Consultation checkout skipping Click ({}) — using Stripe appointmentId={} externalRef={}",
                skipReason,
                appointmentId,
                req.externalRef
            )
            return PaymentGatewayCode.STRIPE to stripeGateway.createCheckout(req)
        }

        try {
            val result = clickGateway.createCheckout(req)
            log.info(
                "Consultation checkout gateway=CLICK appointmentId={} externalRef={} checkoutHost={}",
                appointmentId,
                req.externalRef,
                checkoutUrlHost(result.checkoutUrl)
            )
            return PaymentGatewayCode.CLICK to result
        } catch (e: Exception) {
            log.warn(
                "Consultation checkout Click failed ({}) — falling back to Stripe appointmentId={} externalRef={}",
                e.message,
                appointmentId,
                req.externalRef,
                e
            )
        }
        return PaymentGatewayCode.STRIPE to stripeGateway.createCheckout(req)
    }

    /** Human-readable reason when Click is not attempted (null = Click should be tried). */
    private fun clickSkipReason(): String? {
        if (!clickProperties.enabled) return "CLICK_ENABLED=false"
        if (clickProperties.secretKey.isBlank()) return "CLICK_SECRET_KEY missing or empty"
        if (clickProperties.merchantId <= 0L) return "CLICK_MERCHANT_ID not configured"
        if (clickProperties.serviceId <= 0L) return "CLICK_SERVICE_ID not configured"
        return null
    }

    private fun checkoutUrlHost(checkoutUrl: String): String =
        runCatching { java.net.URI(checkoutUrl).host ?: checkoutUrl.take(80) }
            .getOrElse { checkoutUrl.take(80) }

    /** Only an explicitly app-started checkout gets the in-app redirect on the return pages. */
    private fun returnPlatformQuery(returnPlatform: String?): String =
        if (returnPlatform?.trim()?.lowercase() == "app") "&platform=app" else ""

    /** Our payment id for a gateway return reference, for the app deep link on the return pages (ask 12). */
    @Transactional(readOnly = true)
    fun paymentIdForExternalRef(externalRef: String): Long? =
        externalRef.takeIf { it.isNotBlank() }?.let { paymentRepository.findByExternalRef(it)?.id }

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
        if (payment.status == PaymentStatus.PAID) {
            if (payment.kind == PaymentKind.TREATMENT_PLAN) {
                applyTreatmentPlanPaymentFromGateway(payment)
            }
            return payment
        }
        payment.status = PaymentStatus.PAID
        payment.paidAt = Instant.now()
        payment.updatedAt = Instant.now()
        payment.appointment?.id?.let { settlement.onGatewayPaid(it) }
        val saved = paymentRepository.save(payment)
        when (saved.kind) {
            PaymentKind.CONSULTATION -> notifyDoctorConsultationPaid(saved)
            PaymentKind.TREATMENT_PLAN -> applyTreatmentPlanPaymentFromGateway(saved)
            else -> Unit
        }
        return saved
    }

    @Transactional
    fun markConsultationPaymentCancelledFromClick(merchantTransId: String, detail: String) {
        val payment = paymentRepository.findByExternalRef(merchantTransId) ?: return
        if (payment.gateway != PaymentGatewayCode.CLICK) return
        if (payment.kind != PaymentKind.CONSULTATION && payment.kind != PaymentKind.TREATMENT_PLAN) return
        if (payment.status == PaymentStatus.PAID) return
        payment.status = PaymentStatus.CANCELLED
        payment.failureReason = detail
        payment.updatedAt = Instant.now()
        paymentRepository.save(payment)
        payment.appointment?.id?.let { settlement.onGatewayFailed(it, expired = false) }
    }

    @Transactional
    fun markConsultationCheckoutClosed(externalRef: String?, gatewayPaymentId: String?, expired: Boolean) {
        val payment = when {
            !externalRef.isNullOrBlank() -> paymentRepository.findByExternalRef(externalRef)
            !gatewayPaymentId.isNullOrBlank() -> paymentRepository.findByGatewayPaymentId(gatewayPaymentId)
            else -> null
        } ?: return
        if (payment.status == PaymentStatus.PAID || payment.status == PaymentStatus.REFUNDED) return
        payment.status = if (expired) PaymentStatus.CANCELLED else PaymentStatus.FAILED
        payment.failureReason = if (expired) "Checkout expired" else "Payment failed"
        payment.updatedAt = Instant.now()
        paymentRepository.save(payment)
        payment.appointment?.id?.let { settlement.onGatewayFailed(it, expired) }
    }

    /** Releases the slot locally. The provider session is left to expire on its own. */
    @Transactional
    fun cancelOpenConsultation(appointmentId: Long) {
        val open = paymentRepository.findByAppointment_IdAndStatusInOrderByCreatedAtDesc(appointmentId, OpenCheckoutPolicy.OPEN_STATUSES)
        val now = Instant.now()
        open.forEach { payment ->
            payment.status = PaymentStatus.CANCELLED
            payment.failureReason = "Cancelled by patient"
            payment.updatedAt = now
            paymentRepository.save(payment)
        }
        // shortcut: provider session is not revoked remotely, upgrade when refunds or session cancel is required
    }

    fun enabledConsultationProviders(): List<com.shifa.payment.domain.PaymentGatewayCode> =
        BookingPaymentPolicy.readyProviders(
            configured = paymentProperties.configuredProviders(),
            clickReady = clickSkipReason() == null,
            stripeReady = stripeProperties.secretKey.isNotBlank(),
        )

    data class PaymentMethodQuote(
        val provider: String,
        val amountMinor: Long,
        val currency: String,
    )

    @Transactional(readOnly = true)
    fun listConsultationMethods(serviceId: Long, locationId: Long?): List<PaymentMethodQuote> {
        val service = doctorServices.findById(serviceId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Selected service not found")
        }
        if (!service.isActive) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected service is not active")
        }
        if (service.isFreeConsultation || service.paymentType == com.shifa.domain.ServicePaymentType.PAY_LATER) {
            return emptyList()
        }
        val price = DoctorServicePricing.pickPaymentPrice(
            doctorServicePrices.findByService_IdOrderByCurrencyAsc(service.id),
            locationId,
        ) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected service has no price configured")
        if (!price.currency.equals("UZS", ignoreCase = true)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Online payment is available in UZS only (currency=${price.currency}).",
            )
        }
        return enabledConsultationProviders().map {
            PaymentMethodQuote(provider = it.name, amountMinor = price.amountMinor, currency = price.currency.uppercase())
        }
    }

    @Transactional
    fun markPaymentPaidByGatewayPaymentId(gatewayPaymentId: String): Payment? {
        val payment = paymentRepository.findByGatewayPaymentId(gatewayPaymentId) ?: return null
        if (payment.status == PaymentStatus.PAID) {
            if (payment.kind == PaymentKind.TREATMENT_PLAN) {
                applyTreatmentPlanPaymentFromGateway(payment)
            }
            return payment
        }
        payment.status = PaymentStatus.PAID
        payment.paidAt = Instant.now()
        payment.updatedAt = Instant.now()
        payment.appointment?.id?.let { settlement.onGatewayPaid(it) }
        val saved = paymentRepository.save(payment)
        when (saved.kind) {
            PaymentKind.CONSULTATION -> notifyDoctorConsultationPaid(saved)
            PaymentKind.TREATMENT_PLAN -> applyTreatmentPlanPaymentFromGateway(saved)
            else -> Unit
        }
        return saved
    }

    private fun notifyDoctorConsultationPaid(payment: Payment) {
        if (payment.kind != PaymentKind.CONSULTATION) return
        try {
            val appointmentId = payment.appointment?.id ?: return
            val appointment = appointmentRepository.findByIdWithDoctorAndPatient(appointmentId)
                .orElse(null) ?: return
            val doctor = appointment.doctor
            val patientName = appointment.patient.fullName ?: "Patient"
            val notif = Notification(
                patient = null,
                doctor = doctor,
                title = "Payment received",
                message = "Patient $patientName paid for their appointment.",
                type = Notification.Type.APPOINTMENT_PAID_BY_PATIENT,
                appointmentId = appointment.id,
            )
            val savedNotif = notificationRepository.save(notif)
            fcmService.sendDoctorNotificationAfterCommit(doctor.fcmToken, savedNotif)
        } catch (e: Exception) {
            log.warn("Doctor payment notification failed: {}", e.message)
        }
    }

    private fun notifyDoctorTreatmentPlanPaid(payment: Payment) {
        if (payment.kind != PaymentKind.TREATMENT_PLAN) return
        try {
            val plan = payment.treatmentPlan ?: return
            val doctor = plan.attendingDoctor ?: return
            val patientName = plan.patient.fullName ?: "Patient"
            val amountMajor = payment.amountMinor / 100.0
            val notif = Notification(
                patient = null,
                doctor = doctor,
                title = "Bill payment received",
                message = "Patient $patientName paid $amountMajor ${payment.currency} on treatment plan #${plan.id}.",
                type = Notification.Type.APPOINTMENT_PAID_BY_PATIENT,
                treatmentPlanId = plan.id,
            )
            val savedNotif = notificationRepository.save(notif)
            fcmService.sendDoctorNotificationAfterCommit(doctor.fcmToken, savedNotif)
        } catch (e: Exception) {
            log.warn("Doctor treatment-plan payment notification failed: {}", e.message)
        }
    }
}
