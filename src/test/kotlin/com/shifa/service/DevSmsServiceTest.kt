package com.shifa.service

import com.shifa.config.DevSmsProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DevSmsServiceTest {

    private val service = DevSmsService(
        DevSmsProperties(apiToken = "", baseUrl = "https://devsms.uz/api", senderFrom = "4546")
    )

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
    fun `sendSms returns false when not configured`() {
        assertFalse(service.sendSms("998901234567", "test").success)
    }

    @Test
    fun `isConfigured true when token present`() {
        val configured = DevSmsService(DevSmsProperties(apiToken = "secret"))
        assertTrue(configured.isConfigured())
    }
}
