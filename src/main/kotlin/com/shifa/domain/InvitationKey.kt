package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

/** One-time key used by VerifyKeyScreen before allowing registration/login. */
@Entity @Table(name = "invitation_keys")
class InvitationKey(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "key_code", unique = true, nullable = false)
    val keyCode: String,

    @Column(nullable = false) var consumed: Boolean = false,
    var consumedAt: OffsetDateTime? = null,
    var consumedByUserId: Long? = null,
    
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    
    @Column(name = "expires_at")
    var expiresAt: OffsetDateTime? = null,
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    val createdBy: User? = null,
    
    @Column(name = "email_sent_to")
    var emailSentTo: String? = null,
    
    @Column(name = "email_sent_at")
    var emailSentAt: OffsetDateTime? = null,
    
    @Column(nullable = false)
    var purpose: String = "DOCTOR_ONBOARDING", // DOCTOR_ONBOARDING | PATIENT_INVITE | etc.
    
    @Column(columnDefinition = "TEXT")
    var notes: String? = null
) {
    fun isExpired(): Boolean = expiresAt != null && expiresAt!!.isBefore(OffsetDateTime.now())
    fun isValid(): Boolean = !consumed && !isExpired()
}
