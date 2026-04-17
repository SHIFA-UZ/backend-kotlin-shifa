package com.shifa.security

/**
 * SECURITY: Strong password policy for healthcare application.
 * ALREADY PRESENT: Enforces min length 8, max 128; at least one upper, lower, digit, special character.
 * Prevents brute-force and weak passwords; never log or store plaintext.
 */
object PasswordPolicy {
    /** Minimum length (8–12 recommended; using 8 for usability, 12 for high security). */
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 128

    /** At least one uppercase, one lowercase, one digit, one special character. */
    private val HAS_UPPERCASE = Regex("[A-Z]")
    private val HAS_LOWERCASE = Regex("[a-z]")
    private val HAS_DIGIT = Regex("[0-9]")
    /** At least one special character (non-alphanumeric, excluding space). */
    private val HAS_SPECIAL = Regex("[^A-Za-z0-9\\s]")

    /**
     * Validates password against policy. Returns null if valid, or an error message if invalid.
     * Never log or store the actual password.
     */
    fun validate(password: CharSequence?): String? {
        if (password.isNullOrBlank()) return "Password is required"
        if (password.length < MIN_LENGTH) return "Password must be at least $MIN_LENGTH characters"
        if (password.length > MAX_LENGTH) return "Password must be at most $MAX_LENGTH characters"
        if (!HAS_UPPERCASE.containsMatchIn(password)) return "Password must contain at least one uppercase letter"
        if (!HAS_LOWERCASE.containsMatchIn(password)) return "Password must contain at least one lowercase letter"
        if (!HAS_DIGIT.containsMatchIn(password)) return "Password must contain at least one number"
        if (!HAS_SPECIAL.containsMatchIn(password)) return "Password must contain at least one special character"
        return null
    }
}
