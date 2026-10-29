package com.shifa.clinics.application

import com.shifa.clinics.domain.InvitationKey
import com.shifa.clinics.infrastructure.InvitationKeyRepository
import java.util.Optional
import org.springframework.stereotype.Service

/**
 * The feature's door to InvitationKey records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [InvitationKeyRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class InvitationKeys(private val repo: InvitationKeyRepository) {
    fun findByKeyCode(keyCode: String): InvitationKey? = repo.findByKeyCode(keyCode)
    fun findByClinic_IdAndPurpose(clinicId: Long, purpose: String): List<InvitationKey> = repo.findByClinic_IdAndPurpose(clinicId, purpose)
    fun findByIdAndClinic_Id(id: Long, clinicId: Long): InvitationKey? = repo.findByIdAndClinic_Id(id, clinicId)
    fun nullifyConsumedByUserId(userId: Long): Int = repo.nullifyConsumedByUserId(userId)
    fun nullifyCreatedByUserId(userId: Long): Int = repo.nullifyCreatedByUserId(userId)
    fun findById(id: Long): Optional<InvitationKey> = repo.findById(id)
    fun save(entity: InvitationKey): InvitationKey = repo.save(entity)
}
