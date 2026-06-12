package com.shifa.repo

import com.shifa.domain.SmsVerificationCode
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime
import java.util.Optional

interface SmsVerificationCodeRepository : JpaRepository<SmsVerificationCode, Long> {

    fun findTopByPhoneAndPurposeAndVerifiedFalseOrderByCreatedAtDesc(
        phone: String,
        purpose: String
    ): Optional<SmsVerificationCode>

    fun countByPhoneAndPurposeAndCreatedAtAfter(
        phone: String,
        purpose: String,
        after: OffsetDateTime
    ): Long

    @Modifying
    @Query("DELETE FROM SmsVerificationCode s WHERE s.expiresAt < :cutoff")
    fun deleteExpired(cutoff: OffsetDateTime)
}
