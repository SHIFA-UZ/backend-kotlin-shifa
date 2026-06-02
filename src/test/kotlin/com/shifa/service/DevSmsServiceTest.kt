package com.shifa.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.shifa.config.DevSmsProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DevSmsServiceTest {

    private val service = DevSmsService(
        DevSmsProperties(apiToken = "", baseUrl = "https://devsms.uz/api", senderFrom = "SHIFA UZ")
    )

    @Test
    fun `resolveSenderFrom passes through DevSMS from value`() {
        assertEquals("SHIFA UZ", DevSmsService.resolveSenderFrom("SHIFA UZ"))
        assertEquals("SHIFA UZ", DevSmsService.resolveSenderFrom(""))
        assertEquals("SHIFA.UZ", DevSmsService.resolveSenderFrom("SHIFA.UZ"))
    }

    @Test
    fun `isConfigured false when token blank`() {
        assertFalse(service.isConfigured())
    }

    @Test
    fun `formatPhoneForDevSms strips plus and non-digits`() {
        assertEquals("998901234567", service.formatPhoneForDevSms("+998 90 123 45 67"))
        assertNull(service.formatPhoneForDevSms(null))
        assertNull(service.formatPhoneForDevSms("   "))
    }

    @Test
    fun `formatPhoneForDevSms prepends 998 for local uz mobile`() {
        assertEquals("998902078994", service.formatPhoneForDevSms("902078994"))
    }

    @Test
    fun `sendSms returns false when not configured`() {
        val result = service.sendSms("998901234567", "test")
        assertFalse(result.success)
        assertEquals("SMS service not configured", result.errorMessage)
    }

    @Test
    fun `isConfigured true when token present`() {
        val configured = DevSmsService(DevSmsProperties(apiToken = "secret"))
        assertTrue(configured.isConfigured())
    }

    @Test
    fun `parseDevSmsResponse trims leading whitespace and reads error field`() {
        val body = "\n\n\n{\"success\":false,\"error\":\"Balans yetarli emas\"}\n"
        val parsed = service.parseDevSmsResponse(body)
        assertFalse(parsed.success)
        assertEquals("Balans yetarli emas", parsed.errorMessage)
    }

    @Test
    fun `parseDevSmsResponse reads success payload`() {
        val body = "{\"success\":true,\"data\":{\"sms_id\":42}}"
        val parsed = service.parseDevSmsResponse(body)
        assertTrue(parsed.success)
        assertEquals("42", parsed.smsId)
    }
}
