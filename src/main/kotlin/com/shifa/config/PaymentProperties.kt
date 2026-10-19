package com.shifa.config

import com.shifa.payment.domain.PaymentGatewayCode
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "payment")
class PaymentProperties {
    /** Comma-separated gateway codes, e.g. CLICK,STRIPE. */
    var enabledProviders: String = "CLICK,STRIPE"

    var holdMinutes: Long = 15

    fun configuredProviders(): List<PaymentGatewayCode> =
        enabledProviders.split(',')
            .mapNotNull { raw ->
                val token = raw.trim().uppercase()
                if (token.isEmpty()) null
                else runCatching { PaymentGatewayCode.valueOf(token) }.getOrNull()
            }
            .distinct()
}
