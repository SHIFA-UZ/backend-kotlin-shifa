package com.shifa.payments.api.dto

data class OnboardRequest(
    val refreshUrl: String? = null,
    val returnUrl: String? = null
)
