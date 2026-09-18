package com.shifa.payment.service

import com.shifa.config.AppProperties
import com.shifa.config.ClickProperties
import com.shifa.domain.Appointment
import com.shifa.domain.InstallmentItem
import com.shifa.domain.Notification
import com.shifa.domain.TreatmentPlanPayment
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

    data class TreatmentPlanCheckoutResult(
        val paymentId: Long,
        val externalRef: String,
        val checkoutUrl: String,
        val status: String,
        val gateway: String,
        val amountMinor: Long,
        val currency: String,
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

        val skip = clickSkipReason()
        if (skip != null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Click payments unavailable ($skip)")
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
            gateway = PaymentGatewayCode.CLICK,
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
            successUrl =
            "${appProperties.publicBaseUrl.removeSuffix("/")}/api/payments/checkout/success?ref=$externalRef",
            cancelUrl =
            "${appProperties.publicBaseUrl.removeSuffix("/")}/api/payments/checkout/cancel?ref=$externalRef",
        )
        val gatewayResult = try {
            clickGateway.createCheckout(gatewayCheckoutRequest)
        } catch (e: Exception) {
            log.warn("Treatment plan Click checkout failed planId={}: {}", plan.id, e.message, e)
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Could not start Click checkout: ${e.message}",
            )
        }

        val payee = plan.attendingDoctor
        val payment = paymentRepository.save(
            Payment(
                externalRef = externalRef,
                gateway = PaymentGatewayCode.CLICK,
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
            "Treatment plan checkout gateway=CLICK planId={} installmentItemId={} amountMinor={} externalRef={}",
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
            gateway = PaymentGatewayCode.CLICK.name,
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

        val ledger = financeService.recordPaymentAndSync(
            planId = plan.id,
            amountMinor = payment.amountMinor,
            currency = payment.currency,
            method = TreatmentPlanPayment.PaymentMethod.CLICK,
            memo = "Click ${payment.externalRef}",
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

            PaymentGatewayCode.CLICK,
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
        payment.appointment?.let { apt ->
            apt.paymentStatus = Appointment.PaymentStatus.FAILED
            appointmentRepository.save(apt)
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
