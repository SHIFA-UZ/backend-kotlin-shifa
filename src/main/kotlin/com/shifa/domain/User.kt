package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity @Table(name = "users")
class User(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(unique = true) var email: String? = null,
    @Column(unique = true) var phone: String? = null,
    @Column(unique = true) var username: String? = null,

    @Column(name = "password_hash", nullable = false)
    var passwordHash: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false) var role: Role = Role.DOCTOR,

    @Column(nullable = false) var enabled: Boolean = true,

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false)
    var accountStatus: AccountStatus = AccountStatus.ACTIVE,

    @Column(name = "deletion_requested_at")
    var deletionRequestedAt: OffsetDateTime? = null,

    @Column(name = "deleted_at")
    var deletedAt: OffsetDateTime? = null,

    @Column(name = "phone_original_hash")
    var phoneOriginalHash: String? = null,

    @Column(name = "email_original_hash")
    var emailOriginalHash: String? = null,
    
    @Column(name = "email_verified", nullable = false)
    var emailVerified: Boolean = false,

    @Column(name = "force_password_reset", nullable = false)
    var forcePasswordReset: Boolean = false,
    
    @Column(name = "last_login_at")
    var lastLoginAt: OffsetDateTime? = null,
    
    @Column(name = "failed_login_attempts", nullable = false)
    var failedLoginAttempts: Int = 0,
    
    @Column(name = "locked_until")
    var lockedUntil: OffsetDateTime? = null,

    /**
     * Admin-managed subscription tier controlling feature availability. Drives gating in the
     * doctor and patient apps. PATIENT users may only be PRO or PREMIUM (enforced in
     * SubscriptionTierService).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_tier", nullable = false, length = 16)
    var subscriptionTier: SubscriptionTier = SubscriptionTier.PREMIUM,

    /** Display name / settings for users with [Role.CLINIC_STAFF] (no [DoctorProfile]). */
    @Column(name = "staff_first_name")
    var staffFirstName: String? = null,

    @Column(name = "staff_last_name")
    var staffLastName: String? = null,

    @Column(name = "staff_time_zone")
    var staffTimeZone: String? = null,

    @Column(name = "staff_photo_url")
    var staffPhotoUrl: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
) {
    fun isLocked(): Boolean = lockedUntil != null && lockedUntil!!.isAfter(OffsetDateTime.now())

    enum class AccountStatus {
        ACTIVE,
        PENDING_DELETION,
        DELETED
    }
}
