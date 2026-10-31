package com.shifa.shared.api


import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

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

    @Test
    fun `missing and unconvertible parameters are 400 with the parameter name`() {
        val missing = handler(false).handleMissingParameter(MissingServletRequestParameterException("day", "String"))
        assertEquals(400, missing.statusCode.value())
        assertEquals("Missing required parameter 'day'", missing.body!!["message"])
        assertEquals("VALIDATION", missing.body!!["code"])

        val mismatch = handler(false).handleTypeMismatch(
            MethodArgumentTypeMismatchException("abc", Long::class.java, "id", idParameter(), IllegalArgumentException("abc")),
        )
        assertEquals(400, mismatch.statusCode.value())
        assertEquals("Invalid value for parameter 'id'", mismatch.body!!["message"])
    }

    @Suppress("UNUSED_PARAMETER")
    fun sample(id: String) = Unit

    private fun idParameter() = MethodParameter(ErrorHandlerExposureTest::class.java.getMethod("sample", String::class.java), 0)
}
