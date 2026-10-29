package com.shifa.shared

import com.shifa.shared.config.AppProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PublicUrlsTest {
    private val urls = PublicUrls(AppProperties().apply { publicBaseUrl = "https://api.shifa.uz/"; storageRoot = "/tmp" })

    @Test
    fun `relative paths are joined to the base, absolute ones pass through, blanks are null`() {
        assertEquals("https://api.shifa.uz/doctors/jane-7.jpg", urls.absolute("doctors/jane-7.jpg"))
        assertEquals("https://api.shifa.uz/doctors/jane-7.jpg", urls.absolute("/doctors/jane-7.jpg"))
        assertEquals("https://api.shifa.uz/doctors/jane-7.jpg", urls.absolute(" doctors\\jane-7.jpg "), "trimmed, backslashes become slashes")
        assertEquals("https://cdn.example.com/x.png", urls.absolute("https://cdn.example.com/x.png"))
        assertNull(urls.absolute(null))
        assertNull(urls.absolute("   "))
        assertEquals("http://localhost:8080/a?b=1", PublicUrls.absolute("http://localhost:8080", "a?b=1"))
    }
}
