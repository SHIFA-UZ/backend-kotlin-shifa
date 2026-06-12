package com.shifa.i18n

/**
 * DevSMS-approved OTP message templates (Uzbek).
 */
object SmsOtpFormatting {

    /** Registration OTP — approved by DevSMS. */
    fun registrationOtpBody(code: String): String =
        "Shifa Bemor platformasiga ro'yxatdan o'tish uchun tasdiqlash kodi: $code\n" +
            "Hurmat bilan,\n" +
            "SHIFA UZ"

    /** Forgot-password OTP — submit to DevSMS for approval if not yet approved. */
    fun forgotPasswordOtpBody(code: String): String =
        "Shifa Bemor parolni tiklash uchun tasdiqlash kodi: $code\n" +
            "Hurmat bilan,\n" +
            "SHIFA UZ"
}
