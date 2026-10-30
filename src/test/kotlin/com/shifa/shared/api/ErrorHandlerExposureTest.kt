package com.shifa.shared.api


import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ErrorHandlerExposureTest {
    private fun handler(expose: Boolean) = ErrorHandler(exposeErrorDetails = expose)

    @Test
    fun `deployed profiles return the exception class only - production body unchanged`() {
        val body = handler(expose = false).handleException(IllegalStateException("select * from users where phone='+998'"))!!.body!!
        assertEquals(500, body["error"])
        assertEquals("Internal server error: IllegalStateException", body["message"])
        assertEquals("IllegalStateException", body["exceptionClass"])
        assertEquals(500, body["status"])
        assertEquals("INTERNAL", body["code"])
    }

    @Test
    fun `request id from the MDC is added when present and the legacy keys stay first`() {
        org.slf4j.MDC.put("requestId", "req-12345678")
        try {
            val notFound = org.springframework.web.servlet.resource.NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/x", "/x")
            val body = handler(expose = false).handleNoResourceFound(notFound).body!!
            assertEquals(listOf("error", "message", "status", "code", "requestId"), body.keys.toList())
            assertEquals("req-12345678", body["requestId"])
            assertEquals("NOT_FOUND", body["code"])
        } finally {
            org.slf4j.MDC.clear()
        }
        assertEquals(
            """{"error":"Session invalid","code":"SESSION_INVALID"}""",
            com.shifa.shared.ErrorBodies.filterJson("Session invalid", com.shifa.shared.ErrorCode.SESSION_INVALID),
        )
    }

    @Test
    fun `local profile exposes the message`() {
        val body = handler(expose = true).handleException(IllegalStateException("boom"))!!.body!!
        assertEquals("boom", body["message"])
        assertEquals("IllegalStateException", body["exceptionClass"])
    }
}
