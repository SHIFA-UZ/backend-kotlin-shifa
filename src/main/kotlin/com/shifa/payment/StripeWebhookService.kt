package com.shifa.payment

import com.stripe.model.Charge
import com.stripe.model.Event
import com.stripe.model.PaymentIntent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Handles business logic for incoming Stripe webhook events.
 *
 * Each handler receives the fully-constructed Stripe [Event] and is responsible
 * for updating payment records, triggering notifications, or any other
 * side-effects required for that event type.
 *
 * Extend this service as new event types need to be supported.
 */
@Service
class StripeWebhookService {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Handles `payment_intent.succeeded`.
     *
     * Called when a PaymentIntent transitions to `succeeded`, meaning funds have
     * been captured. Use this to mark an order/appointment as paid and trigger
     * any fulfilment logic.
     */
    fun handlePaymentIntentSucceeded(event: Event) {
        val paymentIntent = event.dataObjectDeserializer
            .deserializeUnsafe() as? PaymentIntent
            ?: run {
                log.warn("payment_intent.succeeded: could not deserialize PaymentIntent (eventId={})", event.id)
                return
            }

        log.info(
            "PaymentIntent succeeded: id={}, amount={}, currency={}, customerId={}",
            paymentIntent.id,
            paymentIntent.amount,
            paymentIntent.currency,
            paymentIntent.customer
        )

        // TODO: Look up the associated appointment/order by paymentIntent.metadata or paymentIntent.id,
        //       mark it as PAID, and send a confirmation notification to the patient.
    }

    /**
     * Handles `payment_intent.payment_failed`.
     *
     * Called when a PaymentIntent fails (e.g. card declined, insufficient funds).
     * Use this to notify the patient and allow them to retry with a different
     * payment method.
     */
    fun handlePaymentIntentFailed(event: Event) {
        val paymentIntent = event.dataObjectDeserializer
            .deserializeUnsafe() as? PaymentIntent
            ?: run {
                log.warn("payment_intent.payment_failed: could not deserialize PaymentIntent (eventId={})", event.id)
                return
            }

        val failureMessage = paymentIntent.lastPaymentError?.message ?: "unknown reason"
        log.warn(
            "PaymentIntent failed: id={}, amount={}, currency={}, reason={}",
            paymentIntent.id,
            paymentIntent.amount,
            paymentIntent.currency,
            failureMessage
        )

        // TODO: Look up the associated appointment/order, mark it as PAYMENT_FAILED,
        //       and notify the patient with a retry link.
    }

    /**
     * Handles `charge.refunded`.
     *
     * Called when a Charge is fully or partially refunded. Use this to update
     * the payment record and notify the patient of the refund.
     */
    fun handleChargeRefunded(event: Event) {
        val charge = event.dataObjectDeserializer
            .deserializeUnsafe() as? Charge
            ?: run {
                log.warn("charge.refunded: could not deserialize Charge (eventId={})", event.id)
                return
            }

        val refundedAmount = charge.amountRefunded
        log.info(
            "Charge refunded: id={}, amountRefunded={}, currency={}, fullyRefunded={}",
            charge.id,
            refundedAmount,
            charge.currency,
            charge.refunded
        )

        // TODO: Look up the associated appointment/order by charge.paymentIntent or charge.metadata,
        //       record the refund amount, and notify the patient.
    }
}
