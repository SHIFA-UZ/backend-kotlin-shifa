package com.shifa.service

enum class SmsOtpSendFailure {
    RATE_LIMITED,
    INVALID_PHONE,
    SMS_PROVIDER_FAILED,
}

sealed class SmsOtpSendResult {
    data object Success : SmsOtpSendResult()

    data class Failure(
        val reason: SmsOtpSendFailure,
        val detail: String? = null,
    ) : SmsOtpSendResult()
}
