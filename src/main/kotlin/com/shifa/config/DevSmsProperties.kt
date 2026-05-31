package com.shifa.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "devsms")
data class DevSmsProperties(
    val apiToken: String = "",
    val baseUrl: String = "https://devsms.uz/api",
    /** Optional sender ID; when blank, DevSMS uses the account default (4546). */
    val senderFrom: String = "",
    /** Billed to doctor per successful appointment reminder SMS (UZS minor units = soums). */
    val pricePerSmsMinor: Long = 500,
    val billingCurrency: String = "UZS",
)
