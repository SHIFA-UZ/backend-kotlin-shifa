package com.shifa.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "devsms")
data class DevSmsProperties(
    val apiToken: String = "",
    val baseUrl: String = "https://devsms.uz/api",
    /**
     * Kimdan (from) in DevSMS API, e.g. "SHIFA UZ". Override with DEVSMS_SENDER_FROM.
     * Must match the name approved in your DevSMS account exactly.
     */
    val senderFrom: String = "SHIFA UZ",
    /** Billed to doctor per successful appointment reminder SMS (UZS minor units = soums). */
    val pricePerSmsMinor: Long = 500,
    val billingCurrency: String = "UZS",
)
