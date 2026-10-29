package com.shifa.notifications.domain

/** Outcome of one SMS send, as the provider reported it. */
data class DevSmsSendResult(
    val success: Boolean,
    val smsId: String? = null,
    val errorMessage: String? = null,
)
