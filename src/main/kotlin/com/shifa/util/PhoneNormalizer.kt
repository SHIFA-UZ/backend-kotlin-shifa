package com.shifa.util

/**
 * Normalizes phone numbers for storage and uniqueness checks.
 * - Trims whitespace
 * - Keeps only digits (and leading + if present)
 * - Produces E.164-like form: +<digits>
 * - Returns null for null or blank input (so empty does not participate in uniqueness)
 */
object PhoneNormalizer {

    private val DIGITS_ONLY = Regex("[^0-9]")

    /**
     * Normalize for storage and uniqueness.
     * @return Normalized E.164-style string (e.g. "+998901234567"), or null if input is null/blank or has no digits.
     */
    @JvmStatic
    fun normalize(phone: String?): String? {
        if (phone.isNullOrBlank()) return null
        val digits = DIGITS_ONLY.replace(phone.trim(), "")
        if (digits.isEmpty()) return null
        return "+$digits"
    }

    /**
     * Returns true if the given string is non-empty after normalization (i.e. should be checked for uniqueness).
     */
    @JvmStatic
    fun isPresent(phone: String?): Boolean = !normalize(phone).isNullOrEmpty()
}
