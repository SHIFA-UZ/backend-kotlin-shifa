package com.shifa.service

import com.shifa.config.AppProperties
import com.shifa.domain.User
import com.shifa.repo.ConversationRepository
import com.shifa.repo.MessageRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.repo.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.OffsetDateTime

@Service
class PatientAccountDeletionService(
    private val users: UserRepository,
    private val userManagementService: com.shifa.service.UserManagementService,
    private val patientProfiles: PatientProfileRepository,
    private val conversations: ConversationRepository,
    private val messages: MessageRepository,
    private val appProperties: AppProperties,
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
        val filename = url.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return
        val storageRoot = Paths.get(appProperties.storageRoot).toAbsolutePath().normalize()
        val path = storageRoot.resolve("chat-attachments").resolve(filename).normalize()
        // Safety: ensure we never delete outside chat-attachments
        if (!path.startsWith(storageRoot.resolve("chat-attachments"))) return
        runCatching {
            if (Files.exists(path)) Files.delete(path)
        }
    }
}

