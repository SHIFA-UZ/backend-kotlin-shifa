package com.shifa.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "devsms")
data class DevSmsProperties(
    val apiToken: String = "",
    val baseUrl: String = "https://devsms.uz/api",
    /**
     * Alphanumeric sender (Kimdan) registered in DevSMS, e.g. SHIFA.UZ (shows as “SHIFA UZ” on phones).
     * Override with DEVSMS_SENDER_FROM. Must match your DevSMS dashboard exactly.
     */
    val senderFrom: String = "SHIFA.UZ",
    /** Billed to doctor per successful appointment reminder SMS (UZS minor units = soums). */
    val pricePerSmsMinor: Long = 500,
    val billingCurrency: String = "UZS",
)
