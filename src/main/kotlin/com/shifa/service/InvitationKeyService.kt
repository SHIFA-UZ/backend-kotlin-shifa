package com.shifa.service

import com.shifa.domain.Clinic
import com.shifa.domain.ClinicMembership
import com.shifa.domain.InvitationKey
import com.shifa.domain.User
import com.shifa.repo.InvitationKeyRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
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
        val keyCode = generateUniqueKeyCode()
        val expiresAt = expiresInDays?.let {
            OffsetDateTime.now().plusDays(it.toLong())
        }

        val key = InvitationKey(
            keyCode = keyCode,
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
        var keyCode: String
        do {
            keyCode = (1..8)
                .map { chars.random() }
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
            .orElseThrow { NoSuchElementException("Key not found") }
        // Mark as consumed to effectively revoke it
        key.consumed = true
        key.consumedAt = OffsetDateTime.now()
        return invitationKeyRepository.save(key)
    }
    
    /**
     * Regenerate a key (create new, mark old as consumed)
     */
    @Transactional
    fun regenerateKey(
        oldKeyId: Long,
        createdBy: User,
        expiresInDays: Int? = null
    ): InvitationKey {
        val oldKey = invitationKeyRepository.findById(oldKeyId)
            .orElseThrow { NoSuchElementException("Key not found") }
        
        // Mark old key as consumed
        oldKey.consumed = true
        oldKey.consumedAt = OffsetDateTime.now()
        invitationKeyRepository.save(oldKey)
        
        // Generate new key with same purpose
        return generateKey(
            createdBy = createdBy,
            expiresInDays = expiresInDays,
            purpose = oldKey.purpose,
            notes = "Regenerated from key ${oldKey.keyCode}",
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
            .orElseThrow { NoSuchElementException("Key not found") }
        key.emailSentTo = email
        key.emailSentAt = OffsetDateTime.now()
        return invitationKeyRepository.save(key)
    }
    
    fun findById(keyId: Long): Optional<InvitationKey> {
        return invitationKeyRepository.findById(keyId)
    }
    
    fun findByKeyCode(keyCode: String): InvitationKey? {
        return invitationKeyRepository.findByKeyCode(keyCode)
    }
    
    fun findAllActive(pageable: Pageable): Page<InvitationKey> {
        return invitationKeyRepository.findActiveKeys(OffsetDateTime.now(), pageable)
    }
    
    fun findAllConsumed(consumed: Boolean, pageable: Pageable): Page<InvitationKey> {
        return invitationKeyRepository.findByConsumedOrderByCreatedAtDesc(consumed, pageable)
    }
    
    fun findByPurpose(purpose: String, pageable: Pageable): Page<InvitationKey> {
        return invitationKeyRepository.findByPurpose(purpose, pageable)
    }
    
    fun findExpiredUnused(pageable: Pageable): Page<InvitationKey> {
        return invitationKeyRepository.findExpiredUnusedKeys(OffsetDateTime.now(), pageable)
    }
}
