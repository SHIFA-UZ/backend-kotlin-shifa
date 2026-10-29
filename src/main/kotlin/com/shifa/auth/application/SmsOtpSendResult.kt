package com.shifa.auth.application

enum class SmsOtpSendFailure {
    /** Generic OTP throttle (legacy alias of send-cap). */
    RATE_LIMITED,
    /** Max SMS sends for this phone+purpose in the window — caller must switch to email. */
    SMS_SEND_LIMIT_REACHED,
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
