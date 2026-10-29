package com.shifa.web

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
    }

    @Test
    fun `local profile exposes the message`() {
        val body = handler(expose = true).handleException(IllegalStateException("boom"))!!.body!!
        assertEquals("boom", body["message"])
        assertEquals("IllegalStateException", body["exceptionClass"])
    }
}
