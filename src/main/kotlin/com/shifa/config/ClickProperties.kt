package com.shifa.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "click")
class ClickProperties {
    /** When false, consultations never use Click (Stripe only for auto mode). */
    var enabled: Boolean = true

    var merchantId: Long = 0
    var serviceId: Long = 0

    /** Merchant user ID in Click (Merchant API); also used in signing some flows — keep configured. */
    var merchantUserId: Long = 0

    var secretKey: String = ""

    /** Payment page URL (GET with query params). */
    var payBaseUrl: String = "https://my.click.uz/services/pay"
}
