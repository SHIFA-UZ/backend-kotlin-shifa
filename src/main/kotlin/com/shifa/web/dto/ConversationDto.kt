package com.shifa.web.dto

import java.time.OffsetDateTime

data class ConversationDto(
    val id: Long,
    val participantName: String,
    val participantPhotoUrl: String?,
    val isDoctorParticipant: Boolean,
    val participantId: Long,
    val lastMessage: String?,
    val lastMessageAt: OffsetDateTime?,
    val unreadCount: Int
)

data class ConversationWithMessagesDto(
    val conversation: ConversationDto,
    val messages: List<MessageDto>
)

/** Request body for POST /api/messages/conversations/start (no message sent). */
data class StartConversationRequest(
    val recipientDoctorId: Long? = null,
    val recipientPatientId: Long? = null
)

data class UserSearchResultDto(
    val id: Long,
    val name: String,
    val photoUrl: String?,
    val isDoctor: Boolean,
    val email: String?,
    val phone: String?
)
