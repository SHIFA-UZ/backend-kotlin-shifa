package com.shifa.shared

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The patient app (apps-flutter-patient, data/api/ErrorMessages.kt) maps these exact backend error messages
 * to localised texts. Until the app matches on the error `code` instead and the builds that match on text are
 * out of support, these strings are frozen: changing one silently breaks the app's error display.
 * The test fails when a frozen message no longer appears anywhere in the backend sources.
 */
class FrozenErrorMessagesTest {
    private val frozen = listOf(
        "Access restricted to doctors.",
        "Account is disabled",
        "Account is disabled.",
        "Account is locked",
        "Appointment does not belong to this patient",
        "Boolean value required",
        "Could not send verification email. Please try again later.",
        "Current password is incorrect",
        "Doctor profile not found",
        "Email OTP required when email is provided",
        "Email already registered",
        "Email verification code required",
        "Firebase verification not configured",
        "Invalid credentials",
        "Invalid credentials - password mismatch",
        "Invalid key",
        "Invalid or expired email verification code",
        "Invalid or expired token",
        "Invalid or expired verification code",
        "Invalid phone number",
        "Key already used",
        "Missing Bearer token",
        "No account found",
        "No admin account found",
        "No doctor account found",
        "No email address on file. Please contact support.",
        "No patient account found",
        "Not a doctor account",
        "Numeric value required",
        "Password is required",
        "Patient with this phone number already exists.",
        "Payment is required before joining this video consultation.",
        "Phone already registered",
        "Phone number not found",
        "SMS could not be sent. Please use email verification or contact support.",
        "Text value required",
        "Too many requests. Please try again later.",
        "Too many verification requests. Please try again later.",
        "User not found",
        "Username is required",
        "Your account has been blocked.",
        "serviceId is required for video consultation",
    )

    @Test
    fun `every message the patient app matches on is still emitted somewhere`() {
        val sources = File("src/main/kotlin").walk().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val lost = frozen.filter { !sources.contains("\"$it\"") } // the complete literal, quotes included
        assertTrue(lost.isEmpty(), "Frozen error messages missing from the backend: $lost")
    }
}
