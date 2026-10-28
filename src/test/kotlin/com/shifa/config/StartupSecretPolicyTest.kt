package com.shifa.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StartupSecretPolicyTest {

    private val longSecret = "x".repeat(64)

    @Test
    fun `local profile is never checked`() {
        val r = StartupSecretPolicy.evaluate(listOf("local"), clickEnabled = true, clickSecretKey = "", jwtSecret = "short")
        assertTrue(r.fatal.isEmpty() && r.warnings.isEmpty())
    }

    @Test
    fun `click enabled with blank secret is fatal in prod`() {
        val r = StartupSecretPolicy.evaluate(listOf("prod"), clickEnabled = true, clickSecretKey = " ", jwtSecret = longSecret)
        assertEquals(1, r.fatal.size)
        assertTrue(r.fatal.single().contains("CLICK_SECRET_KEY"))
    }

    @Test
    fun `click disabled with blank secret is fine`() {
        val r = StartupSecretPolicy.evaluate(listOf("dev"), clickEnabled = false, clickSecretKey = "", jwtSecret = longSecret)
        assertTrue(r.fatal.isEmpty())
    }

    @Test
    fun `short jwt secret only warns, never refuses`() {
        val r = StartupSecretPolicy.evaluate(listOf("prod"), clickEnabled = true, clickSecretKey = "s3cret", jwtSecret = "x".repeat(40))
        assertTrue(r.fatal.isEmpty())
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings.single().contains("40 characters"))
    }

    @Test
    fun `everything set gives no output`() {
        val r = StartupSecretPolicy.evaluate(listOf("prod"), clickEnabled = true, clickSecretKey = "s3cret", jwtSecret = longSecret)
        assertTrue(r.fatal.isEmpty() && r.warnings.isEmpty())
    }
}
