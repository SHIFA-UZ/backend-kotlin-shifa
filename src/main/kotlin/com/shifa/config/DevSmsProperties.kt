package com.shifa.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "devsms")
data class DevSmsProperties(
    val apiToken: String = "",
    val baseUrl: String = "https://devsms.uz/api",
    val senderFrom: String = "4546",
    /** Billed to doctor per successful appointment reminder SMS (UZS minor units = soums). */
    val pricePerSmsMinor: Long = 500,
    val billingCurrency: String = "UZS",
)
