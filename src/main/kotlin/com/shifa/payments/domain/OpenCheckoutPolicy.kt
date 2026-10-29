package com.shifa.payments.domain

import java.time.Instant

/** Rules for handing an existing open checkout back to the patient instead of opening a new one. */
object OpenCheckoutPolicy {
    /** Statuses that mean "the patient may still pay this one". V119 allows one such row per appointment. */
    val OPEN_STATUSES: Set<PaymentStatus> = setOf(PaymentStatus.PENDING, PaymentStatus.AUTHORIZED)

    /**
     * An open checkout is reusable while it is younger than the hold window (gateway links expire),
     * still has a checkout URL, and is on the gateway the patient asked for (or the patient did not ask).
     */
    fun reusable(payment: Payment, preferredGateway: PaymentGatewayCode?, now: Instant, holdMinutes: Long): Boolean =
        payment.status in OPEN_STATUSES &&
            !payment.gatewayCheckoutUrl.isNullOrBlank() &&
            (preferredGateway == null || payment.gateway == preferredGateway) &&
            payment.createdAt.plusSeconds(holdMinutes.coerceAtLeast(1) * 60).isAfter(now)
}
