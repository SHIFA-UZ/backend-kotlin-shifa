package com.shifa.auth.application

import com.shifa.auth.domain.User
import com.shifa.auth.infrastructure.UserRepository
import com.shifa.chat.infrastructure.ConversationRepository
import com.shifa.chat.infrastructure.MessageRepository
import java.security.MessageDigest
import java.time.OffsetDateTime
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class PatientAccountDeletionService(
    private val users: UserRepository,
    private val userManagementService: com.shifa.auth.application.UserManagementService,
    private val patientProfiles: com.shifa.patients.application.PatientProfiles,
    private val conversations: ConversationRepository,
    private val messages: MessageRepository,
    private val storage: com.shifa.shared.storage.FileStorage,
) {
    private val log = LoggerFactory.getLogger(PatientAccountDeletionService::class.java)

    /**
     * Deletes/anonymizes the authenticated patient's account.
     *
     * Retention strategy:
     * - Hard delete: chat conversations/messages (and any stored attachment files referenced by messages)
     * - Retain: appointments, patient documents, clinical notes (they remain linked to patient_profiles.id)
     * - Anonymize identifiers on users + patient_profiles to:
     *   (a) prevent further login, (b) free phone for re-registration, (c) avoid patient-side linkage to old data.
     */
    @Transactional
    fun deletePatientAccount(userId: Long) {
        val user = users.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: $userId")
        }
        if (!user.enabled || user.accountStatus == User.AccountStatus.DELETED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Account already deleted")
        }

        val profile = patientProfiles.findByUserId(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Patient profile not found for user $userId")
        }
        val patientId = profile.id ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient ID not found")

        // 1) Revoke sessions first so access is removed immediately.
        userManagementService.forceLogout(userId, revokedBy = null)

        // 2) Hard delete chat (conversations/messages) + remove stored chat attachment files.
        val patientConvs = conversations.findByPatientParticipantIdOrderByLastMessageDesc(patientId)
        if (patientConvs.isNotEmpty()) {
            val convIds = patientConvs.mapNotNull { it.id }
            // Collect attachment filenames before deleting DB rows.
            convIds.forEach { convId ->
                runCatching {
                    val msgs = messages.findByConversationIdOrderByCreatedAtAsc(convId)
                    msgs.forEach { m ->
                        deleteChatAttachmentIfAny(m.attachmentUrl)
                        deleteChatAttachmentIfAny(m.thumbnailUrl)
                    }
                }
            }
            messages.deleteByConversationIdIn(convIds)
            conversations.deleteAll(patientConvs)
        }

        // 3) Anonymize user + patient profile identifiers (retain medical/legal data).
        val now = OffsetDateTime.now()
        user.phoneOriginalHash = user.phone?.let { sha256Hex(it) }
        user.emailOriginalHash = user.email?.let { sha256Hex(it) }
        user.phone = null
        user.email = null
        user.enabled = false
        user.accountStatus = User.AccountStatus.DELETED
        user.deletedAt = now

        profile.fullName = "Deleted User"
        profile.phone = null
        profile.phoneNormalized = null
        profile.email = null
        profile.address = null
        // Keep other fields (birthDate, chronicDisease, etc.) for legal/clinical retention.

        users.save(user)
        patientProfiles.save(profile)

        log.info("PATIENT_DELETE: Completed deletion/anonymization for userId={}, patientId={}", userId, patientId)
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun deleteChatAttachmentIfAny(url: String?) {
        if (url.isNullOrBlank()) return
        // We only delete files stored in storageRoot/chat-attachments/<filename>
        val filename = (runCatching { java.net.URI(url.trim()).path }.getOrNull() ?: url.substringBefore('#').substringBefore('?'))
            .substringAfterLast('/').takeIf { it.isNotBlank() } ?: return
        // Safety: a bare file name only, so nothing outside chat-attachments can be deleted.
        if (filename.contains('/') || !com.shifa.shared.storage.StorageKeys.valid(filename)) return
        runCatching { storage.delete("chat-attachments/$filename") }
    }
}

