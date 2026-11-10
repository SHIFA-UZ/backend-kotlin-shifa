package com.shifa.clinics.application

import com.shifa.clinics.domain.Clinic
import com.shifa.clinics.domain.ClinicMembership
import com.shifa.clinics.domain.InvitationKey
import com.shifa.auth.domain.User
import com.shifa.clinics.infrastructure.InvitationKeyRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.*

@Service
class InvitationKeyService(
    private val invitationKeyRepository: InvitationKeyRepository
) {
    
    /**
     * Generate a new invitation key
     */
    @Transactional
    fun generateKey(
        createdBy: User? = null,
        expiresInDays: Int? = null,
        purpose: String = InvitationKey.PURPOSE_DOCTOR_ONBOARDING,
        notes: String? = null,
        clinic: Clinic? = null,
        membershipRole: ClinicMembership.MembershipRole? = null,
    ): InvitationKey {
        require(expiresInDays == null || expiresInDays > 0) { "expiresInDays must be positive" }
        return createKey(
            createdBy, expiresInDays?.let { OffsetDateTime.now().plusDays(it.toLong()) }, purpose, notes, clinic, membershipRole,
        )
    }

    private fun createKey(
        createdBy: User?,
        expiresAt: OffsetDateTime?,
        purpose: String,
        notes: String?,
        clinic: Clinic?,
        membershipRole: ClinicMembership.MembershipRole?,
    ): InvitationKey {
        val key = InvitationKey(
            keyCode = generateUniqueKeyCode(),
            createdBy = createdBy,
            expiresAt = expiresAt,
            purpose = purpose,
            notes = notes,
            clinic = clinic,
            membershipRole = membershipRole,
        )

        return invitationKeyRepository.save(key)
    }
    
    /**
     * Generate a unique key code (uppercase alphanumeric, 6-8 chars)
     */
    private fun generateUniqueKeyCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val secureRandom = java.security.SecureRandom()
        var keyCode: String
        do {
            keyCode = (1..8)
                .map { chars[secureRandom.nextInt(chars.length)] }
                .joinToString("")
        } while (invitationKeyRepository.findByKeyCode(keyCode) != null)
        return keyCode
    }
    
    /**
     * Revoke/unrevoke a key
     */
    @Transactional
    fun revokeKey(keyId: Long): InvitationKey {
        val key = invitationKeyRepository.findById(keyId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Token not found") }
        // Mark as consumed to effectively revoke it
        key.consumed = true
        key.consumedAt = OffsetDateTime.now()
        return invitationKeyRepository.save(key)
    }
    
    /**
     * Regenerate a key (create new, mark old as consumed). The new key is valid for [expiresInDays] when given,
     * otherwise for as long as the old key was issued for (no expiry if it had none); the old notes are kept.
     */
    @Transactional
    fun regenerateKey(
        oldKeyId: Long,
        createdBy: User,
        expiresInDays: Int? = null
    ): InvitationKey {
        require(expiresInDays == null || expiresInDays > 0) { "expiresInDays must be positive" }
        val oldKey = invitationKeyRepository.findById(oldKeyId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Token not found") }
        
        // Mark old key as consumed
        oldKey.consumed = true
        oldKey.consumedAt = OffsetDateTime.now()
        invitationKeyRepository.save(oldKey)
        
        val now = OffsetDateTime.now()
        val expiresAt = expiresInDays?.let { now.plusDays(it.toLong()) }
            ?: oldKey.expiresAt?.let { now.plus(java.time.Duration.between(oldKey.createdAt, it)) }
        return createKey(
            createdBy = createdBy,
            expiresAt = expiresAt,
            purpose = oldKey.purpose,
            notes = listOfNotNull(oldKey.notes?.takeIf { it.isNotBlank() }, "Regenerated from key ${oldKey.keyCode}")
                .joinToString("\n"),
            clinic = oldKey.clinic,
            membershipRole = oldKey.membershipRole,
        )
    }
    
    /**
     * Mark email as sent
     */
    @Transactional
    fun markEmailSent(keyId: Long, email: String): InvitationKey {
        val key = invitationKeyRepository.findById(keyId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Token not found") }
        key.emailSentTo = email
        key.emailSentAt = OffsetDateTime.now()
        return invitationKeyRepository.save(key)
    }
    
    @Transactional(readOnly = true)
    fun findById(keyId: Long): Optional<InvitationKey> {
        return invitationKeyRepository.findById(keyId)
    }
    
    @Transactional(readOnly = true)
    fun findByKeyCode(keyCode: String): InvitationKey? {
        return invitationKeyRepository.findByKeyCode(keyCode)
    }
    
    @Transactional(readOnly = true)
    fun findAllActive(pageable: Pageable): Page<InvitationKey> {
        return invitationKeyRepository.findActiveKeys(OffsetDateTime.now(), pageable)
    }
    
    /** The admin list filters; ACTIVE is unused and not expired, EXPIRED is unused and past its expiry. */
    enum class KeyStatus { ACTIVE, EXPIRED, CONSUMED, ALL }

    /**
     * The admin token list. [status] wins when given. Without it the older parameters keep their meaning:
     * [consumed] alone ignores the expiry, [purpose] alone lists every key of that purpose, and no filter at all
     * lists the keys that are not expired (used ones included).
     */
    @Transactional(readOnly = true)
    fun findForAdmin(status: KeyStatus?, consumed: Boolean?, purpose: String?, pageable: Pageable): Page<InvitationKey> {
        val (consumedFilter, expiredFilter) = when (status) {
            KeyStatus.ACTIVE -> false to false
            KeyStatus.EXPIRED -> false to true
            KeyStatus.CONSUMED -> true to null
            KeyStatus.ALL -> null to null
            null -> when {
                consumed != null -> consumed to null
                purpose != null -> null to null
                else -> null to false
            }
        }
        return invitationKeyRepository.findForAdmin(consumedFilter, expiredFilter, purpose, OffsetDateTime.now(), pageable)
    }

    @Transactional(readOnly = true)
    fun findAllConsumed(consumed: Boolean, pageable: Pageable): Page<InvitationKey> {
        return invitationKeyRepository.findByConsumedOrderByCreatedAtDesc(consumed, pageable)
    }
    
    @Transactional(readOnly = true)
    fun findByPurpose(purpose: String, pageable: Pageable): Page<InvitationKey> {
        return invitationKeyRepository.findByPurpose(purpose, pageable)
    }
    
    @Transactional(readOnly = true)
    fun findExpiredUnused(pageable: Pageable): Page<InvitationKey> {
        return invitationKeyRepository.findExpiredUnusedKeys(OffsetDateTime.now(), pageable)
    }
}
