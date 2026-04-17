package com.shifa.service

import com.shifa.domain.EmailVerificationCode
import com.shifa.repo.EmailVerificationCodeRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import kotlin.random.Random

/**
 * DB-backed email OTP service. Generates codes, persists them, sends via Brevo SMTP,
 * and verifies on submission. Rate-limited to 5 requests per email per hour per purpose.
 */
@Service
class EmailOtpService(
    private val repo: EmailVerificationCodeRepository,
    private val emailSender: EmailSenderService
) {
    private val log = LoggerFactory.getLogger(EmailOtpService::class.java)

    private fun generateCode(): String = Random.nextInt(100_000, 1_000_000).toString()

    /**
     * Generate a code, persist it, and send the email. Returns true on success.
     * Rate-limited: max 5 codes per email+purpose per hour.
     */
    @Transactional
    fun sendCode(email: String, purpose: String): Boolean {
        val normalized = email.trim().lowercase()
        val oneHourAgo = OffsetDateTime.now().minusHours(1)
        val recentCount = repo.countByEmailAndPurposeAndCreatedAtAfter(normalized, purpose, oneHourAgo)
        if (recentCount >= 5) {
            log.warn("Rate limit reached for {} [{}]", normalized.take(3) + "***", purpose)
            return false
        }
        val code = generateCode()
        repo.save(EmailVerificationCode(
            email = normalized,
            code = code,
            purpose = purpose
        ))
        emailSender.sendOtpEmail(normalized, code, purpose)
        return true
    }

    /**
     * Verify a code. Returns true if valid (correct code, not expired, under max attempts).
     * On success, marks the code as verified. On failure, increments attempt counter.
     */
    @Transactional
    fun verify(email: String, code: String): Boolean {
        val normalized = email.trim().lowercase()
        val entry = repo.findTopByEmailAndPurposeAndVerifiedFalseOrderByCreatedAtDesc(
            normalized, EmailVerificationCode.PURPOSE_REGISTRATION
        ).orElse(null)
        return verifyEntry(entry, code)
    }

    /**
     * Verify a code for a specific purpose. Preferred over [verify] for clarity.
     */
    @Transactional
    fun verify(email: String, code: String, purpose: String): Boolean {
        val normalized = email.trim().lowercase()
        val entry = repo.findTopByEmailAndPurposeAndVerifiedFalseOrderByCreatedAtDesc(normalized, purpose)
            .orElse(null)
        return verifyEntry(entry, code)
    }

    private fun verifyEntry(entry: EmailVerificationCode?, code: String): Boolean {
        if (entry == null) return false
        if (entry.isExpired()) return false
        if (entry.attempts >= EmailVerificationCode.MAX_ATTEMPTS) return false
        if (entry.code != code.trim()) {
            entry.attempts++
            repo.save(entry)
            return false
        }
        entry.verified = true
        repo.save(entry)
        return true
    }

    /**
     * Legacy compatibility: store and get code (used by existing endpoints that
     * already handle sending). Returns the generated code.
     */
    @Transactional
    fun storeAndGetCode(email: String): String {
        val normalized = email.trim().lowercase()
        val code = generateCode()
        repo.save(EmailVerificationCode(
            email = normalized,
            code = code,
            purpose = EmailVerificationCode.PURPOSE_REGISTRATION
        ))
        log.info("Email OTP stored for {} (expires in 10 min)", normalized.take(3) + "***")
        return code
    }

    fun clear(email: String) {
        // No-op for DB-backed; codes expire naturally or get verified
    }

    @Scheduled(fixedRate = 3600000) // every hour
    @Transactional
    fun cleanupExpired() {
        repo.deleteExpired(OffsetDateTime.now())
    }
}
