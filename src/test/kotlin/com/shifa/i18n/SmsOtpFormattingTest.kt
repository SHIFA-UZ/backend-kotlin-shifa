package com.shifa.i18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmsOtpFormattingTest {

    @Test
    fun `registration OTP uses DevSMS-approved template`() {
        val body = SmsOtpFormatting.registrationOtpBody("123456")
        assertEquals(
            "Shifa Bemor platformasiga ro'yxatdan o'tish uchun tasdiqlash kodi: 123456\n" +
                "Hurmat bilan,\n" +
                "SHIFA UZ",
            body
        )
    }

    @Test
    fun `forgot password OTP template`() {
        val body = SmsOtpFormatting.forgotPasswordOtpBody("654321")
        assertEquals(
            "Shifa Bemor parolni tiklash uchun tasdiqlash kodi: 654321\n" +
                "Hurmat bilan,\n" +
                "SHIFA UZ",
            body
        )
    }
}
