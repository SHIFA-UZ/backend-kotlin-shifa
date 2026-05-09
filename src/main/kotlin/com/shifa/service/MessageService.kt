package com.shifa.service

import com.shifa.config.AppProperties
import com.shifa.domain.Conversation
import com.shifa.domain.Message
import com.shifa.domain.Notification
import com.shifa.domain.Role
import com.shifa.repo.*
import com.shifa.web.dto.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.OffsetDateTime

@Service
class MessageService(
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val notificationRepo: NotificationRepository,
    private val userRepo: UserRepository,
    private val doctorProfileRepo: DoctorProfileRepository,
    private val patientProfileRepo: PatientProfileRepository,
    private val fcmService: FcmService,
    private val appProps: AppProperties
    ) {
    private val log = LoggerFactory.getLogger(MessageService::class.java)
    
        @Transactional(readOnly = true)
        fun listConversations(doctorUserId: Long): List<ConversationDto> {
            val conversations = conversationRepo.findByDoctorUserIdOrderByLastMessageDesc(doctorUserId)
            return conversations.map { conv ->
            val participantName = getParticipantName(conv, doctorUserId)
            val participantPhotoUrl = getParticipantPhotoUrl(conv, doctorUserId)
            val isDoctor = conv.doctorParticipant != null
            val participantId = if (isDoctor) {
                // If current doctor is the owner, participant is doctorParticipant
                // If current doctor is the participant, participant is doctorUser
                if (conv.doctorUser!!.id == doctorUserId) {
                    conv.doctorParticipant!!.id
                } else {
                    conv.doctorUser!!.id
                }
            } else {
                conv.patientParticipant!!.id!!
            }

            val messages = messageRepo.findByConversationIdOrderByCreatedAtAsc(conv.id!!)
            val lastMessage = messages.lastOrNull()
            val unreadCount = messages.count { !it.isRead && it.senderUser!!.id != doctorUserId }

            ConversationDto(
                id = conv.id!!,
                participantName = participantName,
                participantPhotoUrl = participantPhotoUrl,
                isDoctorParticipant = isDoctor,
                participantId = participantId,
                lastMessage = lastMessage?.let { lastMessagePreview(it) },
                lastMessageAt = conv.lastMessageAt,
                unreadCount = unreadCount
            )
        }
        }
    
        /** Start or get a conversation with a recipient without sending any message. */
        @Transactional
        fun startOrGetConversation(
            doctorUserId: Long,
            recipientDoctorId: Long?,
            recipientPatientId: Long?
        ): ConversationDto {
            val conversation = when {
                recipientDoctorId != null -> findOrCreateConversation(doctorUserId, recipientDoctorId, null)
                recipientPatientId != null -> findOrCreateConversation(doctorUserId, null, recipientPatientId)
                else -> throw IllegalArgumentException("Must provide recipientDoctorId or recipientPatientId")
            }
            val participantName = getParticipantName(conversation, doctorUserId)
            val participantPhotoUrl = getParticipantPhotoUrl(conversation, doctorUserId)
            val isDoctor = conversation.doctorParticipant != null
            val participantId = if (isDoctor) {
                if (conversation.doctorUser!!.id == doctorUserId) {
                    conversation.doctorParticipant!!.id
                } else {
                    conversation.doctorUser!!.id
                }
            } else {
                conversation.patientParticipant!!.id!!
            }
            return ConversationDto(
                id = conversation.id!!,
                participantName = participantName,
                participantPhotoUrl = participantPhotoUrl,
                isDoctorParticipant = isDoctor,
                participantId = participantId,
                lastMessage = null,
                lastMessageAt = conversation.lastMessageAt,
                unreadCount = 0
            )
        }

        @Transactional(readOnly = true)
        fun getConversationWithMessages(conversationId: Long, doctorUserId: Long): ConversationWithMessagesDto {
            val conversation = conversationRepo.findById(conversationId)
                .orElseThrow { IllegalArgumentException("Conversation not found: $conversationId") }

        // Allow access if doctor is either the owner or the participant
        val hasAccess = conversation.doctorUser!!.id == doctorUserId ||
                (conversation.doctorParticipant != null && conversation.doctorParticipant!!.id == doctorUserId) ||
                (conversation.patientParticipant != null && conversation.patientParticipant!!.id == doctorUserId)
        
        if (!hasAccess) {
            throw IllegalArgumentException("Access denied")
        }

        val messages = messageRepo.findByConversationIdOrderByCreatedAtAsc(conversationId)
        val participantName = getParticipantName(conversation, doctorUserId)
        val participantPhotoUrl = getParticipantPhotoUrl(conversation, doctorUserId)
        val isDoctor = conversation.doctorParticipant != null
        val participantId = if (isDoctor) {
            // If current doctor is the owner, participant is doctorParticipant
            // If current doctor is the participant, participant is doctorUser
            if (conversation.doctorUser!!.id == doctorUserId) {
                conversation.doctorParticipant!!.id
            } else {
                conversation.doctorUser!!.id
            }
        } else {
            conversation.patientParticipant!!.id!!
        }

        val unreadCount = messages.count { !it.isRead && it.senderUser!!.id != doctorUserId }

        val conversationDto = ConversationDto(
            id = conversation.id!!,
            participantName = participantName,
            participantPhotoUrl = participantPhotoUrl,
            isDoctorParticipant = isDoctor,
            participantId = participantId,
            lastMessage = messages.lastOrNull()?.let { lastMessagePreview(it) },
            lastMessageAt = conversation.lastMessageAt,
            unreadCount = unreadCount
        )

        val doctorUserIdInConv = conversation.doctorUser?.id
        val otherDoctorUserIdInConv = conversation.doctorParticipant?.id
        val patientUserIdInConv = try { conversation.patientParticipant?.user?.id } catch (_: Exception) { null }
        val messageDtos = messages.map { msg ->
            messageToDto(msg, doctorUserId, doctorUserIdInConv, otherDoctorUserIdInConv, patientUserIdInConv)
        }

        return ConversationWithMessagesDto(conversation = conversationDto, messages = messageDtos)
    }

    @Transactional
    fun sendMessage(doctorUserId: Long, request: SendMessageRequest): MessageDto {
        val senderUser = userRepo.findById(doctorUserId)
            .orElseThrow { IllegalArgumentException("User not found: $doctorUserId") }

        // Find or create conversation
        val conversation = when {
            request.conversationId != null -> {
                conversationRepo.findById(request.conversationId!!)
                    .orElseThrow { IllegalArgumentException("Conversation not found") }
            }
            request.recipientDoctorId != null -> {
                findOrCreateConversation(doctorUserId, request.recipientDoctorId!!, null)
            }
            request.recipientPatientId != null -> {
                findOrCreateConversation(doctorUserId, null, request.recipientPatientId!!)
            }
            else -> throw IllegalArgumentException("Must provide conversationId or recipient")
        }

        // Allow access if doctor is either the owner or the participant
        val hasAccess = conversation.doctorUser!!.id == doctorUserId ||
                (conversation.doctorParticipant != null && conversation.doctorParticipant!!.id == doctorUserId) ||
                (conversation.patientParticipant != null && conversation.patientParticipant!!.id == doctorUserId)
        
        if (!hasAccess) {
            throw IllegalArgumentException("Access denied")
        }

        // Determine recipient based on who the sender is
        // If sender is the owner, recipient is the participant
        // If sender is the participant, recipient is the owner
        val recipientDoctor = when {
            conversation.doctorUser!!.id == doctorUserId -> {
                // Sender is the owner, recipient is the participant (if doctor-to-doctor)
                conversation.doctorParticipant
            }
            conversation.doctorParticipant != null && conversation.doctorParticipant!!.id == doctorUserId -> {
                // Sender is the participant, recipient is the owner
                conversation.doctorUser
            }
            else -> null
        }
        
        val recipientPatient = if (conversation.patientParticipant != null) {
            conversation.patientParticipant
        } else {
            null
        }

        // Determine message type
        val messageType = when {
            request.type != null -> Message.MessageType.valueOf(request.type.uppercase())
            request.attachmentUrl != null -> {
                when {
                    request.attachmentName?.lowercase()?.endsWith(".pdf") == true -> Message.MessageType.DOCUMENT
                    request.attachmentName?.lowercase()?.let { it.endsWith(".doc") || it.endsWith(".docx") } == true -> Message.MessageType.DOCUMENT
                    request.attachmentName?.lowercase()?.let { 
                        it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".gif")
                    } == true -> Message.MessageType.IMAGE
                    request.attachmentName?.lowercase()?.let {
                        it.endsWith(".mp3") || it.endsWith(".wav") || it.endsWith(".m4a") || it.endsWith(".ogg")
                    } == true -> Message.MessageType.VOICE
                    else -> Message.MessageType.DOCUMENT
                }
            }
            else -> Message.MessageType.TEXT
        }

        // Create message
        val message = Message(
            conversation = conversation,
            senderUser = senderUser,
            recipientDoctor = recipientDoctor,
            recipientPatient = recipientPatient,
            text = request.text,
            messageType = messageType,
            attachmentUrl = request.attachmentUrl,
            attachmentName = request.attachmentName,
            thumbnailUrl = request.thumbnailUrl,
            fileSize = request.fileSize,
            duration = request.duration,
            status = Message.MessageStatus.SENT,
            isRead = false,
            createdAt = OffsetDateTime.now()
        )

        val saved = messageRepo.save(message)

        // Update conversation's last message time
        conversation.lastMessageAt = OffsetDateTime.now()
        conversationRepo.save(conversation)

        // Push notification to patient when doctor sends a chat message.
        // Includes chatId for deep-linking to chat screen in patient app.
        if (recipientPatient != null && senderUser.role == Role.DOCTOR) {
            sendPatientChatMessageNotification(
                senderDoctorUserId = senderUser.id,
                recipientPatient = recipientPatient,
                conversationId = conversation.id,
                message = saved
            )
        }

        val doctorUserIdInConv = conversation.doctorUser?.id
        val otherDoctorUserIdInConv = conversation.doctorParticipant?.id
        val patientUserIdInConv = try { conversation.patientParticipant?.user?.id } catch (_: Exception) { null }
        return messageToDto(saved, doctorUserId, doctorUserIdInConv, otherDoctorUserIdInConv, patientUserIdInConv)
    }

    @Transactional
    fun markAsRead(conversationId: Long, doctorUserId: Long) {
        val conversation = conversationRepo.findById(conversationId)
            .orElseThrow { IllegalArgumentException("Conversation not found") }

        // Allow access if doctor is either the owner or the participant
        val hasAccess = conversation.doctorUser!!.id == doctorUserId ||
                (conversation.doctorParticipant != null && conversation.doctorParticipant!!.id == doctorUserId) ||
                (conversation.patientParticipant != null && conversation.patientParticipant!!.id == doctorUserId)
        
        if (!hasAccess) {
            throw IllegalArgumentException("Access denied")
        }

        val messages = messageRepo.findByConversationIdOrderByCreatedAtAsc(conversationId)
        messages.forEach { msg ->
            if (msg.senderUser!!.id != doctorUserId && !msg.isRead) {
                msg.isRead = true
                messageRepo.save(msg)
            }
        }
    }

    fun searchUsers(doctorUserId: Long, query: String): List<UserSearchResultDto> {
        val results = mutableListOf<UserSearchResultDto>()

        // Search doctors - use database query instead of loading all
        val doctorUsers = userRepo.searchByRoleAndQuery(doctorUserId, Role.DOCTOR, query)
        
        doctorUsers.forEach { user ->
            val profile = doctorProfileRepo.findByUserId(user.id).orElse(null)
            if (profile != null) {
                val name = "${profile.lastName} ${profile.firstName}".trim()
                results.add(
                    UserSearchResultDto(
                        id = user.id,
                        name = name,
                        photoUrl = profile.avatarUrl,
                        isDoctor = true,
                        email = user.email,
                        phone = user.phone
                    )
                )
            }
        }

        // Search patients - use database query instead of loading all
        val patients = patientProfileRepo.searchByQuery(query)

        patients.forEach { patient ->
            results.add(
                UserSearchResultDto(
                    id = patient.id!!,
                    name = patient.fullName,
                    photoUrl = patient.photoUrl,
                    isDoctor = false,
                    email = patient.email,
                    phone = patient.phone
                )
            )
        }

        return results.distinctBy { it.id }
    }

    fun getUnreadCount(doctorUserId: Long): Long {
        return messageRepo.countUnreadMessagesForDoctor(doctorUserId)
    }

    // -------------------- Patient-facing methods --------------------

    fun listConversationsForPatient(patientProfileId: Long, patientUserId: Long): List<ConversationDto> {
        val conversations = conversationRepo.findByPatientParticipantIdOrderByLastMessageDesc(patientProfileId)
        return conversations.map { conv ->
            val doctorUserId = conv.doctorUser!!.id
            val participantName = getDoctorNameForPatient(conv)
            val participantPhotoUrl = getDoctorPhotoForPatient(conv)

            val messages = messageRepo.findByConversationIdOrderByCreatedAtAsc(conv.id!!)
            val lastMessage = messages.lastOrNull()
            val unreadCount = messages.count { !it.isRead && it.recipientPatient?.id == patientProfileId }

            ConversationDto(
                id = conv.id!!,
                participantName = participantName,
                participantPhotoUrl = participantPhotoUrl,
                isDoctorParticipant = true,
                participantId = doctorUserId,
                lastMessage = lastMessage?.let { lastMessagePreview(it) },
                lastMessageAt = conv.lastMessageAt,
                unreadCount = unreadCount
            )
        }
    }

    fun getConversationWithMessagesForPatient(
        conversationId: Long,
        patientProfileId: Long,
        patientUserId: Long
    ): ConversationWithMessagesDto {
        val conversation = conversationRepo.findById(conversationId)
            .orElseThrow { IllegalArgumentException("Conversation not found: $conversationId") }

        if (conversation.patientParticipant?.id != patientProfileId) {
            throw IllegalArgumentException("Access denied")
        }

        val messages = messageRepo.findByConversationIdOrderByCreatedAtAsc(conversationId)
        val participantName = getDoctorNameForPatient(conversation)
        val participantPhotoUrl = getDoctorPhotoForPatient(conversation)

        val unreadCount = messages.count { !it.isRead && it.recipientPatient?.id == patientProfileId }

        val conversationDto = ConversationDto(
            id = conversation.id!!,
            participantName = participantName,
            participantPhotoUrl = participantPhotoUrl,
            isDoctorParticipant = true,
            participantId = conversation.doctorUser!!.id,
            lastMessage = messages.lastOrNull()?.let { lastMessagePreview(it) },
            lastMessageAt = conversation.lastMessageAt,
            unreadCount = unreadCount
        )

        val doctorUserIdInConv = conversation.doctorUser?.id
        val otherDoctorUserIdInConv = conversation.doctorParticipant?.id
        val patientUserIdInConv = try { conversation.patientParticipant?.user?.id } catch (_: Exception) { null }
        val messageDtos = messages.map { msg ->
            messageToDto(msg, patientUserId, doctorUserIdInConv, otherDoctorUserIdInConv, patientUserIdInConv)
        }

        return ConversationWithMessagesDto(conversation = conversationDto, messages = messageDtos)
    }

    @Transactional
    fun sendMessageAsPatient(
        patientUserId: Long,
        patientProfileId: Long,
        request: SendMessageRequest
    ): MessageDto {
        val senderUser = userRepo.findById(patientUserId)
            .orElseThrow { IllegalArgumentException("User not found: $patientUserId") }

        val conversation = when {
            request.conversationId != null -> {
                conversationRepo.findById(request.conversationId!!)
                    .orElseThrow { IllegalArgumentException("Conversation not found") }
            }
            request.recipientDoctorId != null -> {
                findOrCreateConversationForPatient(request.recipientDoctorId!!, patientProfileId)
            }
            else -> throw IllegalArgumentException("Must provide conversationId or recipientDoctorId")
        }

        if (conversation.patientParticipant?.id != patientProfileId) {
            throw IllegalArgumentException("Access denied")
        }

        val recipientDoctor = conversation.doctorUser

        // Determine message type
        val messageType = when {
            request.type != null -> Message.MessageType.valueOf(request.type.uppercase())
            request.attachmentUrl != null -> {
                when {
                    request.attachmentName?.lowercase()?.endsWith(".pdf") == true -> Message.MessageType.DOCUMENT
                    request.attachmentName?.lowercase()?.let { it.endsWith(".doc") || it.endsWith(".docx") } == true -> Message.MessageType.DOCUMENT
                    request.attachmentName?.lowercase()?.let { 
                        it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".gif")
                    } == true -> Message.MessageType.IMAGE
                    request.attachmentName?.lowercase()?.let {
                        it.endsWith(".mp3") || it.endsWith(".wav") || it.endsWith(".m4a") || it.endsWith(".ogg")
                    } == true -> Message.MessageType.VOICE
                    else -> Message.MessageType.DOCUMENT
                }
            }
            else -> Message.MessageType.TEXT
        }

        val message = Message(
            conversation = conversation,
            senderUser = senderUser,
            recipientDoctor = recipientDoctor,
            recipientPatient = null,
            text = request.text,
            messageType = messageType,
            attachmentUrl = request.attachmentUrl,
            attachmentName = request.attachmentName,
            thumbnailUrl = request.thumbnailUrl,
            fileSize = request.fileSize,
            duration = request.duration,
            status = Message.MessageStatus.SENT,
            isRead = false,
            createdAt = OffsetDateTime.now()
        )

        val saved = messageRepo.save(message)
        conversation.lastMessageAt = OffsetDateTime.now()
        conversationRepo.save(conversation)

        val doctorUserIdInConv = conversation.doctorUser?.id
        val otherDoctorUserIdInConv = conversation.doctorParticipant?.id
        val patientUserIdInConv = try { conversation.patientParticipant?.user?.id } catch (_: Exception) { null }
        return messageToDto(saved, patientUserId, doctorUserIdInConv, otherDoctorUserIdInConv, patientUserIdInConv)
    }

    @Transactional
    fun markAsReadForPatient(conversationId: Long, patientProfileId: Long) {
        val conversation = conversationRepo.findById(conversationId)
            .orElseThrow { IllegalArgumentException("Conversation not found") }

        if (conversation.patientParticipant?.id != patientProfileId) {
            throw IllegalArgumentException("Access denied")
        }

        val messages = messageRepo.findByConversationIdOrderByCreatedAtAsc(conversationId)
        messages.forEach { msg ->
            if (msg.recipientPatient?.id == patientProfileId && !msg.isRead) {
                msg.isRead = true
                messageRepo.save(msg)
            }
        }
    }

    fun searchDoctorsForPatient(query: String): List<UserSearchResultDto> {
        // Use database query instead of loading all doctors into memory
        val doctors = doctorProfileRepo.searchByQuery(query)

        return doctors.map { profile ->
            val name = "${profile.lastName} ${profile.firstName}".trim()
            UserSearchResultDto(
                id = profile.user.id,
                name = name,
                photoUrl = normalizePhotoUrl(profile.avatarUrl),
                isDoctor = true,
                email = profile.user.email,
                phone = profile.user.phone
            )
        }.distinctBy { it.id }
    }

    fun getUnreadCountForPatient(patientProfileId: Long): Long {
        return messageRepo.countUnreadMessagesForPatient(patientProfileId)
    }

    private fun findOrCreateConversation(
        doctorUserId: Long,
        doctorParticipantId: Long?,
        patientParticipantId: Long?
    ): Conversation {
        val doctorUser = userRepo.findById(doctorUserId)
            .orElseThrow { IllegalArgumentException("Doctor user not found") }

        val existing = when {
            doctorParticipantId != null -> {
                // Check both directions: current doctor as owner OR as participant
                val asOwner = conversationRepo.findByDoctorUserAndParticipant(doctorUserId, doctorParticipantId)
                if (asOwner != null) return asOwner
                // Check if conversation exists with other doctor as owner
                conversationRepo.findByDoctorUserAndParticipant(doctorParticipantId, doctorUserId)
            }
            patientParticipantId != null -> {
                conversationRepo.findByDoctorUserAndParticipant(doctorUserId, patientParticipantId)
            }
            else -> null
        }

        if (existing != null) {
            return existing
        }

        // Create new conversation with current doctor as owner
        val newConversation = Conversation(
            doctorUser = doctorUser,
            doctorParticipant = doctorParticipantId?.let { userRepo.findById(it).orElse(null) },
            patientParticipant = patientParticipantId?.let { patientProfileRepo.findById(it).orElse(null) },
            createdAt = OffsetDateTime.now(),
            updatedAt = OffsetDateTime.now()
        )

        return conversationRepo.save(newConversation)
    }

    private fun getParticipantName(conversation: Conversation, currentDoctorId: Long): String {
        return when {
            conversation.doctorParticipant != null -> {
                // For doctor-to-doctor: show the OTHER doctor (not the current one)
                val otherDoctorId = if (conversation.doctorUser!!.id == currentDoctorId) {
                    conversation.doctorParticipant!!.id
                } else {
                    conversation.doctorUser!!.id
                }
                val profile = doctorProfileRepo.findByUserId(otherDoctorId).orElse(null)
                if (profile != null) {
                    "${profile.lastName} ${profile.firstName}".trim()
                } else {
                    "Unknown Doctor"
                }
            }
            conversation.patientParticipant != null -> {
                conversation.patientParticipant!!.fullName
            }
            else -> "Unknown"
        }
    }

    private fun getParticipantPhotoUrl(conversation: Conversation, currentDoctorId: Long): String? {
        return when {
            conversation.doctorParticipant != null -> {
                // For doctor-to-doctor: show the OTHER doctor (not the current one)
                val otherDoctorId = if (conversation.doctorUser!!.id == currentDoctorId) {
                    conversation.doctorParticipant!!.id
                } else {
                    conversation.doctorUser!!.id
                }
                val profile = doctorProfileRepo.findByUserId(otherDoctorId).orElse(null)
                normalizePhotoUrl(profile?.avatarUrl)
            }
            conversation.patientParticipant != null -> {
                normalizePhotoUrl(conversation.patientParticipant!!.photoUrl)
            }
            else -> null
        }
    }

    private fun getDoctorNameForPatient(conversation: Conversation): String {
        val doctorUserId = conversation.doctorUser!!.id
        val profile = doctorProfileRepo.findByUserId(doctorUserId).orElse(null)
        return if (profile != null) {
            "${profile.lastName} ${profile.firstName}".trim()
        } else {
            "Unknown Doctor"
        }
    }

    private fun getDoctorPhotoForPatient(conversation: Conversation): String? {
        val doctorUserId = conversation.doctorUser!!.id
        val profile = doctorProfileRepo.findByUserId(doctorUserId).orElse(null)
        return normalizePhotoUrl(profile?.avatarUrl)
    }

    private fun findOrCreateConversationForPatient(
        doctorUserId: Long,
        patientProfileId: Long
    ): Conversation {
        val existing = conversationRepo.findByDoctorUserAndPatient(doctorUserId, patientProfileId)
        if (existing != null) {
            return existing
        }

        val doctorUser = userRepo.findById(doctorUserId)
            .orElseThrow { IllegalArgumentException("Doctor user not found") }
        val patientProfile = patientProfileRepo.findById(patientProfileId)
            .orElseThrow { IllegalArgumentException("Patient profile not found") }

        val newConversation = Conversation(
            doctorUser = doctorUser,
            doctorParticipant = null,
            patientParticipant = patientProfile,
            createdAt = OffsetDateTime.now(),
            updatedAt = OffsetDateTime.now()
        )

        return conversationRepo.save(newConversation)
    }

    private fun normalizePhotoUrl(photoUrl: String?): String? {
        val trimmed = photoUrl?.trim()?.replace("\\", "/") ?: return null
        if (trimmed.isEmpty()) return null
        val isAbs = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        if (isAbs) return trimmed
        val base = appProps.publicBaseUrl.removeSuffix("/")
        val path = trimmed.removePrefix("/")
        return "$base/$path"
    }

    private fun sendPatientChatMessageNotification(
        senderDoctorUserId: Long,
        recipientPatient: com.shifa.domain.PatientProfile,
        conversationId: Long?,
        message: Message
    ) {
        val chatId = conversationId ?: run {
            log.warn("CHAT_PUSH: skipped (conversation id null)")
            return
        }
        val patientProfileId = recipientPatient.id ?: run {
            log.warn("CHAT_PUSH: skipped (patient profile id null)")
            return
        }

        val doctorName = try {
            doctorProfileRepo.findByUserId(senderDoctorUserId)
                .map { "${it.lastName} ${it.firstName}".trim() }
                .orElse("Doctor")
        } catch (e: Exception) {
            log.warn("CHAT_PUSH: doctor name lookup failed: {}", e.message)
            "Doctor"
        }

        val body = message.text?.trim()?.takeIf { it.isNotEmpty() } ?: lastMessagePreview(message)
        val notificationRow = try {
            notificationRepo.save(
                Notification(
                    patient = recipientPatient,
                    doctor = null,
                    title = "New message",
                    message = "$doctorName: $body",
                    type = Notification.Type.CHAT_MESSAGE,
                    appointmentId = null,
                    documentAccessRequestId = null,
                    taskId = null
                )
            )
        } catch (e: Exception) {
            log.warn("CHAT_PUSH: failed to save notification: {}", e.message, e)
            return
        }

        notificationRepo.flush()

        val notificationId = notificationRow.id
        val extraData = mapOf(
            "chatId" to chatId.toString(),
            "entityId" to chatId.toString(),
            "notificationId" to notificationId.toString()
        )

        val deliverPush: () -> Unit = lambda@{
            try {
                val token = patientProfileRepo.findById(patientProfileId).orElse(null)?.fcmToken
                if (token.isNullOrBlank()) {
                    log.info("CHAT_PUSH: no FCM token for patientProfileId={}", patientProfileId)
                    return@lambda
                }
                val notif = notificationRepo.findById(notificationId).orElse(null)
                if (notif == null) {
                    log.warn("CHAT_PUSH: notification id={} not found after commit", notificationId)
                    return@lambda
                }
                val ok = fcmService.sendPatientNotification(token, notif, extraData)
                if (!ok) {
                    log.warn(
                        "CHAT_PUSH: FCM sendPatientNotification returned false (patientProfileId={}, notificationId={})",
                        patientProfileId,
                        notificationId
                    )
                }
            } catch (e: Exception) {
                log.warn("CHAT_PUSH: FCM delivery failed: {}", e.message, e)
            }
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        deliverPush()
                    }
                }
            )
        } else {
            deliverPush()
        }
    }

    /** Preview text for conversation list: use message text or a label for image/voice/document. */
    private fun lastMessagePreview(message: Message): String {
        val text = message.text?.trim()
        if (!text.isNullOrBlank()) return text
        val type = when {
            message.messageType == Message.MessageType.IMAGE -> "Photo"
            message.messageType == Message.MessageType.VOICE -> "Voice message"
            message.messageType == Message.MessageType.DOCUMENT -> "Document"
            message.attachmentUrl != null -> {
                when {
                    message.attachmentName?.lowercase()?.endsWith(".pdf") == true -> "Document"
                    message.attachmentName?.lowercase()?.let { it.endsWith(".doc") || it.endsWith(".docx") } == true -> "Document"
                    message.attachmentName?.lowercase()?.let {
                        it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".gif")
                    } == true -> "Photo"
                    message.attachmentName?.lowercase()?.let {
                        it.endsWith(".mp3") || it.endsWith(".wav") || it.endsWith(".m4a") || it.endsWith(".ogg")
                    } == true -> "Voice message"
                    else -> "Document"
                }
            }
            else -> "Message"
        }
        return type
    }

    /**
     * Convert Message entity to MessageDto with new structure.
     * senderRole is derived from participant user IDs (resolved once at call site to avoid lazy-load issues in loops).
     */
    private fun messageToDto(
        message: Message,
        currentUserId: Long,
        doctorUserIdInConv: Long?,
        otherDoctorUserIdInConv: Long?,
        patientUserIdInConv: Long?
    ): MessageDto {
        val senderId = message.senderUser?.id ?: 0L
        val senderRole = when {
            senderId == doctorUserIdInConv -> "doctor"
            senderId == otherDoctorUserIdInConv -> "doctor"
            senderId == patientUserIdInConv -> "patient"
            else -> "doctor" // fallback for legacy or edge cases
        }
        
        // Determine message type from content
        val messageType = when {
            message.messageType != null -> message.messageType.name.lowercase()
            message.attachmentUrl != null -> {
                when {
                    message.attachmentName?.lowercase()?.endsWith(".pdf") == true -> "document"
                    message.attachmentName?.lowercase()?.let { it.endsWith(".doc") || it.endsWith(".docx") } == true -> "document"
                    message.attachmentName?.lowercase()?.let { 
                        it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".gif")
                    } == true -> "image"
                    message.attachmentName?.lowercase()?.let {
                        it.endsWith(".mp3") || it.endsWith(".wav") || it.endsWith(".m4a") || it.endsWith(".ogg")
                    } == true -> "voice"
                    else -> "document"
                }
            }
            else -> "text"
        }

        val content = MessageContentDto(
            text = message.text,
            fileUrl = normalizePhotoUrl(message.attachmentUrl),
            thumbnailUrl = normalizePhotoUrl(message.thumbnailUrl),
            fileName = message.attachmentName,
            fileSize = message.fileSize,
            duration = message.duration
        )

        val status = when {
            message.status != null -> message.status.name.lowercase()
            message.isRead -> "read"
            else -> "sent"
        }

        return MessageDto(
            id = message.id!!,
            chatId = message.conversation!!.id!!,
            senderId = senderId,
            senderRole = senderRole,
            type = messageType,
            content = content,
            status = status,
            isMine = senderId == currentUserId,
            isRead = message.isRead,
            createdAt = message.createdAt
        )
    }
}
