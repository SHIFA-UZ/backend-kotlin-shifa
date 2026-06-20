package com.shifa.service

import com.shifa.domain.SmsVerificationCode
import com.shifa.i18n.SmsOtpFormatting
import com.shifa.repo.SmsVerificationCodeRepository
import com.shifa.util.PhoneNormalizer
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import kotlin.random.Random

@Service
class SmsOtpService(
    private val repo: SmsVerificationCodeRepository,
    private val devSmsService: DevSmsService,
) {
    private val log = LoggerFactory.getLogger(SmsOtpService::class.java)

    private fun generateCode(): String = Random.nextInt(100_000, 1_000_000).toString()

  /**
   * Generate a code, persist it, and send SMS.
   * Rate-limited: max 5 codes per phone+purpose per hour.
   */
    fun sendCode(phone: String, purpose: String): SmsOtpSendResult {
        val normalized = PhoneNormalizer.normalize(phone)
            ?: return SmsOtpSendResult.Failure(SmsOtpSendFailure.INVALID_PHONE)
        if (!PhoneNormalizer.isUzbekMobile(normalized)) {
            log.warn("SMS OTP rejected for non-Uzbek phone: {}***", normalized.take(6))
            return SmsOtpSendResult.Failure(SmsOtpSendFailure.INVALID_PHONE)
        }
        val code = persistNewCode(normalized, purpose)
            ?: return SmsOtpSendResult.Failure(SmsOtpSendFailure.RATE_LIMITED)
        val message = when (purpose) {
            SmsVerificationCode.PURPOSE_REGISTRATION -> SmsOtpFormatting.registrationOtpBody(code)
            SmsVerificationCode.PURPOSE_FORGOT_PASSWORD -> SmsOtpFormatting.forgotPasswordOtpBody(code)
            else -> SmsOtpFormatting.registrationOtpBody(code)
        }
        val result = devSmsService.sendSms(normalized, message)
        if (!result.success) {
            log.warn("DevSMS OTP send failed for {}***: {}", normalized.take(6), result.errorMessage)
            return SmsOtpSendResult.Failure(
                SmsOtpSendFailure.SMS_PROVIDER_FAILED,
                result.errorMessage,
            )
        }
        return SmsOtpSendResult.Success
    }

    @Transactional
    fun persistNewCode(phone: String, purpose: String): String? {
        val normalized = PhoneNormalizer.normalize(phone) ?: return null
        val oneHourAgo = OffsetDateTime.now().minusHours(1)
        val recentCount = repo.countByPhoneAndPurposeAndCreatedAtAfter(normalized, purpose, oneHourAgo)
        if (recentCount >= 5) {
            log.warn("SMS OTP rate limit for {}*** [{}]", normalized.take(6), purpose)
            return null
        }
        val code = generateCode()
        repo.save(
            SmsVerificationCode(
                phone = normalized,
                code = code,
                purpose = purpose,
            )
        )
        return code
    }

    @Transactional
    fun verify(phone: String, code: String): Boolean {
        val normalized = PhoneNormalizer.normalize(phone) ?: return false
        val entry = repo.findTopByPhoneAndPurposeAndVerifiedFalseOrderByCreatedAtDesc(
            normalized,
            SmsVerificationCode.PURPOSE_REGISTRATION
        ).orElse(null)
        return verifyEntry(entry, code)
    }

    @Transactional
    fun verify(phone: String, code: String, purpose: String): Boolean {
        val normalized = PhoneNormalizer.normalize(phone) ?: return false
        val entry = repo.findTopByPhoneAndPurposeAndVerifiedFalseOrderByCreatedAtDesc(normalized, purpose)
            .orElse(null)
        return verifyEntry(entry, code)
    }

    private fun verifyEntry(entry: SmsVerificationCode?, code: String): Boolean {
        if (entry == null) return false
        if (entry.isExpired()) return false
        if (entry.attempts >= SmsVerificationCode.MAX_ATTEMPTS) return false
        if (entry.code != code.trim()) {
            entry.attempts++
            repo.save(entry)
            return false
        }
        entry.verified = true
        repo.save(entry)
        return true
    }

    @Scheduled(fixedRate = 3600000)
    @Transactional
    fun cleanupExpired() {
        repo.deleteExpired(OffsetDateTime.now())
    }
}
