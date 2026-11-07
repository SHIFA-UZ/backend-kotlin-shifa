package com.shifa.payments.infrastructure

import com.shifa.payments.application.StripeConnectAccountFlags
import com.shifa.payments.application.StripeConnectAccounts
import com.shifa.shared.config.StripeProperties
import com.stripe.exception.StripeException
import com.stripe.model.Account
import com.stripe.net.RequestOptions
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** `GET /v1/accounts/{id}` with short timeouts and its own request options (the global `Stripe.apiKey` is untouched). */
@Component
class StripeConnectAccountClient(private val stripeProperties: StripeProperties) : StripeConnectAccounts {
    private val log = LoggerFactory.getLogger(StripeConnectAccountClient::class.java)

    override fun flags(accountId: String): StripeConnectAccountFlags? {
        val apiKey = stripeProperties.secretKey.trim().takeIf { it.isNotEmpty() } ?: return null
        val options = RequestOptions.builder()
            .setApiKey(apiKey)
            .setConnectTimeout(CONNECT_TIMEOUT_MS)
            .setReadTimeout(READ_TIMEOUT_MS)
            .build()
        return try {
            val account = Account.retrieve(accountId, options)
            StripeConnectAccountFlags(
                detailsSubmitted = account.detailsSubmitted == true,
                chargesEnabled = account.chargesEnabled == true,
                transfersActive = account.capabilities?.transfers == "active",
                disabledReason = account.requirements?.disabledReason,
            )
        } catch (e: StripeException) {
            log.warn("Stripe Connect status unavailable: {}", e.javaClass.simpleName)
            null
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 5_000
    }
}
