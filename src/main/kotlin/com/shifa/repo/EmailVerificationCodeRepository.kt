package com.shifa.repo

import com.shifa.domain.EmailVerificationCode
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime
import java.util.Optional

interface EmailVerificationCodeRepository : JpaRepository<EmailVerificationCode, Long> {

    fun findTopByEmailAndPurposeAndVerifiedFalseOrderByCreatedAtDesc(
        email: String, purpose: String
    ): Optional<EmailVerificationCode>

    fun countByEmailAndPurposeAndCreatedAtAfter(
        email: String, purpose: String, after: OffsetDateTime
    ): Long

    @Modifying
    @Query("DELETE FROM EmailVerificationCode e WHERE e.expiresAt < :cutoff")
    fun deleteExpired(cutoff: OffsetDateTime)
}
