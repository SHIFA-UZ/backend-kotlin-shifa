package com.shifa.service

import com.shifa.domain.SmsVerificationCode
import com.shifa.i18n.SmsOtpFormatting
import com.shifa.repo.SmsVerificationCodeRepository
import com.shifa.util.PhoneNormalizer
import org.slf4j.LoggerFactory
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
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
   * Hard-capped: max [MAX_SMS_SENDS_PER_HOUR] codes per phone+purpose per hour.
   * After the cap, the client must switch to email — DevSMS may accept a send
   * that Eskiz later rejects, so we cannot rely on provider failure alone.
   */
    fun sendCode(phone: String, purpose: String): SmsOtpSendResult {
        val normalized = PhoneNormalizer.normalize(phone)
            ?: return SmsOtpSendResult.Failure(SmsOtpSendFailure.INVALID_PHONE)
        if (!PhoneNormalizer.isUzbekMobile(normalized)) {
            log.warn("SMS OTP rejected for non-Uzbek phone: {}***", normalized.take(6))
            return SmsOtpSendResult.Failure(SmsOtpSendFailure.INVALID_PHONE)
        }
        val code = persistNewCode(normalized, purpose)
            ?: return SmsOtpSendResult.Failure(SmsOtpSendFailure.SMS_SEND_LIMIT_REACHED)
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
        if (recentCount >= MAX_SMS_SENDS_PER_HOUR) {
            log.warn(
                "SMS OTP send limit ({}/h) for {}*** [{}]",
                MAX_SMS_SENDS_PER_HOUR,
                normalized.take(6),
                purpose,
            )
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

    companion object {
        /** Max SMS OTP sends per phone+purpose per rolling hour. */
        const val MAX_SMS_SENDS_PER_HOUR = 3
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
    @SchedulerLock(name = "sms-otp-cleanup", lockAtLeastFor = "PT20S")
    @Transactional
    fun cleanupExpired() {
        repo.deleteExpired(OffsetDateTime.now())
    }
}
