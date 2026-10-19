package com.shifa.service

import com.shifa.domain.Appointment
import com.shifa.domain.DoctorService
import com.shifa.domain.ServicePaymentType
import com.shifa.payment.domain.PaymentGatewayCode
import java.time.Duration
import java.time.Instant

/**
 * Booking payment rules shared by the patient API and tests.
 * Free is [DoctorService.isFreeConsultation]. [ServicePaymentType] is only pay-now or pay-at-clinic.
 */
object BookingPaymentPolicy {
    const val LOCKED_24H = "APPOINTMENT_LOCKED_24H"
    val LOCK_WINDOW: Duration = Duration.ofHours(24)

    enum class Kind { FREE, PAY_LATER, PAY_NOW, LEGACY }

    fun videoAllowed(service: DoctorService): Boolean =
        service.isFreeConsultation || service.paymentType == ServicePaymentType.PAY_NOW

    fun kind(service: DoctorService?): Kind = when {
        service == null -> Kind.LEGACY
        service.isFreeConsultation -> Kind.FREE
        service.paymentType == ServicePaymentType.PAY_LATER -> Kind.PAY_LATER
        else -> Kind.PAY_NOW
    }

    /** True when the visit starts in less than 24 hours, including visits already in the past. */
    fun isLocked24h(startAt: Instant, now: Instant): Boolean =
        Duration.between(now, startAt) < LOCK_WINDOW

    fun paymentBadge(status: Appointment.PaymentStatus): String? = when (status) {
        Appointment.PaymentStatus.PAID -> "PAID"
        Appointment.PaymentStatus.PAY_IN_CLINIC -> "PAY_IN_CLINIC"
        else -> null
    }

    fun canPatientChange(status: Appointment.Status, locked: Boolean): Boolean {
        if (locked) return false
        return status == Appointment.Status.CONFIRMED ||
            status == Appointment.Status.REQUESTED ||
            status == Appointment.Status.PENDING_PAYMENT
    }

    fun readyProviders(
        configured: List<PaymentGatewayCode>,
        clickReady: Boolean,
        stripeReady: Boolean,
    ): List<PaymentGatewayCode> =
        configured.filter {
            when (it) {
                PaymentGatewayCode.CLICK -> clickReady
                PaymentGatewayCode.STRIPE -> stripeReady
                else -> false
            }
        }.distinct()

    /**
     * Null keeps the current two-step checkout (book, then POST /payments/consultation/checkout)
     * so the existing app can pay later from the appointments list.
     * A value means create the provider session during booking and hold the slot.
     * The new app sends the provider from GET /payments/methods even when that list has one item.
     */
    fun chooseProvider(requested: String?, enabled: List<PaymentGatewayCode>): PaymentGatewayCode? {
        if (requested.isNullOrBlank()) return null
        val code = runCatching { PaymentGatewayCode.valueOf(requested.trim().uppercase()) }.getOrNull()
            ?: throw IllegalArgumentException("Provider not enabled")
        if (code !in enabled) throw IllegalArgumentException("Provider not enabled")
        return code
    }
}
