package com.shifa.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class FileUrlSignerTest {

    private val now = Instant.parse("2026-10-28T12:00:00Z")
    private val secret = "a_really_long_random_64+_char_secret_development_only_xxxxxxxx"
    private val signer = FileUrlSigner(secret, Clock.fixed(now, ZoneOffset.UTC))

    private fun params(url: String): Map<String, String> =
        URI(url).rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun `signed document url verifies and expires after the ttl`() {
        val signed = signer.sign("https://api.shifa.uz/patientdocuments/12/report-123.pdf")
        val p = params(signed)
        assertEquals(now.plus(Duration.ofHours(1)).epochSecond.toString(), p["exp"])
        assertTrue(signer.verify("/patientdocuments/12/report-123.pdf", p["exp"], p["sig"]))

        val later = FileUrlSigner(secret, Clock.fixed(now.plus(Duration.ofMinutes(61)), ZoneOffset.UTC))
        assertFalse(later.verify("/patientdocuments/12/report-123.pdf", p["exp"], p["sig"]))
        val atExpiry = FileUrlSigner(secret, Clock.fixed(now.plus(Duration.ofHours(1)), ZoneOffset.UTC))
        assertFalse(atExpiry.verify("/patientdocuments/12/report-123.pdf", p["exp"], p["sig"]))
    }

    @Test
    fun `signature is bound to the path and the expiry`() {
        val p = params(signer.sign("https://api.shifa.uz/patients/anna-7.jpg"))
        assertFalse(signer.verify("/patients/anna-8.jpg", p["exp"], p["sig"]))
        assertFalse(signer.verify("/patients/anna-7.jpg", (p["exp"]!!.toLong() + 1).toString(), p["sig"]))
        assertFalse(signer.verify("/patients/anna-7.jpg", p["exp"], "AAAA"))
        assertFalse(signer.verify("/patients/anna-7.jpg", null, p["sig"]))
    }

    @Test
    fun `hostile expiry values never throw`() {
        assertFalse(signer.verify("/patients/a.jpg", Long.MAX_VALUE.toString(), "x"))
        assertFalse(signer.verify("/patients/a.jpg", Long.MIN_VALUE.toString(), "x"))
        assertFalse(signer.verify("/patients/a.jpg", "not-a-number", "x"))
        assertFalse(signer.verify("/patients/a.jpg", "", ""))
    }

    @Test
    fun `host does not matter, so stored urls on the old hostname keep working`() {
        val old = signer.sign("https://shifa-doc-backend-mvp-production.up.railway.app/chat-attachments/x.jpg")
        assertTrue(old.startsWith("https://shifa-doc-backend-mvp-production.up.railway.app/chat-attachments/x.jpg?exp="))
        val p = params(old)
        assertTrue(signer.verify("/chat-attachments/x.jpg", p["exp"], p["sig"]))
    }

    @Test
    fun `public paths and empty values are left untouched`() {
        assertEquals("https://api.shifa.uz/doctors/avatar.jpg", signer.sign("https://api.shifa.uz/doctors/avatar.jpg"))
        assertEquals("https://api.shifa.uz/certificates/c.png", signer.sign("https://api.shifa.uz/certificates/c.png"))
        assertNull(signer.signNullable(null))
        assertEquals("", signer.sign(""))
    }

    @Test
    fun `allowed prefixes restrict what a context may sign`() {
        val doc = "https://api.shifa.uz/patientdocuments/3/x.pdf"
        assertEquals(doc, signer.sign(doc, allowed = setOf(FileUrlSigner.PATIENT_PHOTOS)))
        assertTrue(signer.sign("https://api.shifa.uz/patients/a-3.jpg", allowed = setOf(FileUrlSigner.PATIENT_PHOTOS)).contains("sig="))
    }

    @Test
    fun `an already signed url gets a fresh signature, not a second one`() {
        val old = FileUrlSigner(secret, Clock.fixed(now.minus(Duration.ofHours(5)), ZoneOffset.UTC))
        val stale = old.sign("https://api.shifa.uz/chat-attachments/x.jpg")
        val fresh = signer.sign(stale)
        val p = params(fresh)
        assertEquals(1, fresh.split("exp=").size - 1)
        assertEquals(now.plus(Duration.ofHours(1)).epochSecond.toString(), p["exp"])
        assertTrue(signer.verify("/chat-attachments/x.jpg", p["exp"], p["sig"]))
    }

    @Test
    fun `canonical strips the signature and keeps everything else`() {
        val signed = signer.sign("https://api.shifa.uz/chat-attachments/x.jpg?v=2#frag")
        assertTrue(signed.startsWith("https://api.shifa.uz/chat-attachments/x.jpg?v=2&exp="))
        assertTrue(signed.endsWith("#frag"))
        assertEquals("https://api.shifa.uz/chat-attachments/x.jpg?v=2#frag", signer.canonical(signed))
        assertEquals("patients/a-7.jpg", signer.canonical("patients/a-7.jpg"))
        assertNull(signer.canonical(null))
    }

    @Test
    fun `relative protected path is signed over its decoded path`() {
        val once = signer.sign("patients/anna%20b-7.jpg")
        assertTrue(once.startsWith("patients/anna%20b-7.jpg?exp="))
        val p = once.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        // The servlet path the filter sees is decoded.
        assertTrue(signer.verify("/patients/anna b-7.jpg", p["exp"], p["sig"]))
    }

    @Test
    fun `ambiguous paths are not signed`() {
        assertEquals("/patients/../patientdocuments/1/x.pdf", signer.sign("/patients/../patientdocuments/1/x.pdf"))
        assertEquals("/patients//x.jpg", signer.sign("/patients//x.jpg"))
        assertEquals("https://api.shifa.uz/patients/./x.jpg", signer.sign("https://api.shifa.uz/patients/./x.jpg"))
    }

    @Test
    fun `different secrets do not verify each other`() {
        val other = FileUrlSigner("another-secret-another-secret-another-secret-another-secret-xx", Clock.fixed(now, ZoneOffset.UTC))
        val p = params(signer.sign("https://api.shifa.uz/patients/anna-7.jpg"))
        assertFalse(other.verify("/patients/anna-7.jpg", p["exp"], p["sig"]))
    }
}

class FileUrlSignerEncodingTest {
    private val now = java.time.Instant.parse("2026-10-28T12:00:00Z")
    private val signer = FileUrlSigner("a_really_long_random_64+_char_secret_development_only_xxxxxxxx", java.time.Clock.fixed(now, java.time.ZoneOffset.UTC))

    @org.junit.jupiter.api.Test
    fun `percent-encoded dot segments are not signed`() {
        val url = "https://api.shifa.uz/patients/%2e%2e/patientdocuments/1/x.pdf"
        org.junit.jupiter.api.Assertions.assertEquals(url, signer.sign(url))
    }

    @org.junit.jupiter.api.Test
    fun `percent-encoded reserved parameter names are stripped and replaced`() {
        val tricky = "https://api.shifa.uz/chat-attachments/x.jpg?%65xp=1&%73ig=old&v=2"
        org.junit.jupiter.api.Assertions.assertEquals("https://api.shifa.uz/chat-attachments/x.jpg?v=2", signer.canonical(tricky))
        val signed = signer.sign(tricky)
        org.junit.jupiter.api.Assertions.assertEquals(1, signed.split("exp=").size - 1)
        org.junit.jupiter.api.Assertions.assertFalse(signed.contains("%65xp"))
    }

    @org.junit.jupiter.api.Test
    fun `canonical strips a signature even from a url the signer would not sign`() {
        org.junit.jupiter.api.Assertions.assertEquals(
            "/patients/../x.jpg",
            signer.canonical("/patients/../x.jpg?exp=1&sig=abc"),
        )
    }
}
