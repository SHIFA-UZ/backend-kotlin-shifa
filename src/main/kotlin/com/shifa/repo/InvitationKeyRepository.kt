package com.shifa.repo

import com.shifa.domain.InvitationKey
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

@Repository
interface InvitationKeyRepository : JpaRepository<InvitationKey, Long> {
    fun findByKeyCode(keyCode: String): InvitationKey?

    @Query(
        "SELECT k FROM InvitationKey k WHERE k.clinic.id = :clinicId AND k.purpose = :purpose ORDER BY k.id DESC"
    )
    fun findByClinic_IdAndPurpose(
        @Param("clinicId") clinicId: Long,
        @Param("purpose") purpose: String,
    ): List<InvitationKey>

    fun findByIdAndClinic_Id(id: Long, clinicId: Long): InvitationKey?
    
    fun findByConsumedOrderByCreatedAtDesc(consumed: Boolean, pageable: Pageable): Page<InvitationKey>
    
    @Query("SELECT k FROM InvitationKey k WHERE k.purpose = :purpose ORDER BY k.id DESC")
    fun findByPurpose(@Param("purpose") purpose: String, pageable: Pageable): Page<InvitationKey>
    
    @Query("SELECT k FROM InvitationKey k WHERE k.expiresAt IS NULL OR k.expiresAt > :now ORDER BY k.id DESC")
    fun findActiveKeys(@Param("now") now: OffsetDateTime, pageable: Pageable): Page<InvitationKey>
    
    @Query("SELECT k FROM InvitationKey k WHERE k.expiresAt IS NOT NULL AND k.expiresAt <= :now AND k.consumed = false ORDER BY k.id DESC")
    fun findExpiredUnusedKeys(@Param("now") now: OffsetDateTime, pageable: Pageable): Page<InvitationKey>

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE InvitationKey k SET k.consumedByUserId = null WHERE k.consumedByUserId = :userId")
    fun nullifyConsumedByUserId(@Param("userId") userId: Long): Int

    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE invitation_keys SET created_by_user_id = NULL WHERE created_by_user_id = :userId", nativeQuery = true)
    fun nullifyCreatedByUserId(@Param("userId") userId: Long): Int
}
