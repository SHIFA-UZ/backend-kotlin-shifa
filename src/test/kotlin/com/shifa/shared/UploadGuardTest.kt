package com.shifa.shared

import com.shifa.config.AppProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.server.ResponseStatusException

class UploadGuardTest {
    private fun guard(enforce: Boolean) = UploadGuard(AppProperties().apply { uploadPolicyEnforce = enforce })
    private val html = MockMultipartFile("file", "evil.html", "text/html; name=\"Jane.html\"", byteArrayOf(1))
    private val pdf = MockMultipartFile("file", "scan.pdf", "application/pdf", byteArrayOf(1))

    @Test
    fun `enforcement off lets everything through`() {
        guard(false).check(UploadPolicy.Kind.CHAT_ATTACHMENT, html)
        guard(false).check(UploadPolicy.Kind.DOCUMENT, pdf)
    }

    @Test
    fun `enforcement on refuses disallowed types with 400 and keeps allowed ones`() {
        val e = assertThrows(ResponseStatusException::class.java) { guard(true).check(UploadPolicy.Kind.CHAT_ATTACHMENT, html) }
        assertEquals(HttpStatus.BAD_REQUEST, e.statusCode)
        guard(true).check(UploadPolicy.Kind.DOCUMENT, pdf)
    }
}
