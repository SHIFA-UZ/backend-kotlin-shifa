package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "email_verification_codes")
class EmailVerificationCode(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val email: String,

    @Column(nullable = false)
    val code: String,

    @Column(nullable = false)
    val purpose: String,

    @Column(nullable = false)
    var verified: Boolean = false,

    @Column(nullable = false)
    var attempts: Int = 0,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "expires_at", nullable = false)
    val expiresAt: OffsetDateTime = OffsetDateTime.now().plusMinutes(10)
) {
    companion object {
        const val PURPOSE_REGISTRATION = "REGISTRATION"
        const val PURPOSE_LOGIN = "LOGIN"
        /** Admin panel: password verified first, then email OTP before JWT is issued */
        const val PURPOSE_ADMIN_LOGIN = "ADMIN_LOGIN"
        const val PURPOSE_FORGOT_PASSWORD = "FORGOT_PASSWORD"
        const val PURPOSE_ACCOUNT_DELETION = "ACCOUNT_DELETION"
        const val MAX_ATTEMPTS = 5
    }

    fun isExpired(): Boolean = OffsetDateTime.now().isAfter(expiresAt)
}
