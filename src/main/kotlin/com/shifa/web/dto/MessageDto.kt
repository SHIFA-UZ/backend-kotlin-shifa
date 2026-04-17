package com.shifa.web.dto

import com.shifa.domain.Message
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

data class MessageDto(
    val id: Long,
    val chatId: Long, // conversationId
    val senderId: Long,
    val senderRole: String, // "doctor" or "patient"
    val type: String, // "text", "image", "voice", "document", "system"
    val content: MessageContentDto,
    val status: String, // "sending", "sent", "delivered", "read"
    val isMine: Boolean,
    val isRead: Boolean,
    val createdAt: OffsetDateTime
)

data class MessageContentDto(
    val text: String? = null,
    val fileUrl: String? = null,
    val thumbnailUrl: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val duration: Int? = null // For voice messages in seconds
)

// SECURITY (NEW): Size limits to prevent XSS/oversized payloads; validation enforced when @Valid is used
data class SendMessageRequest(
    val conversationId: Long? = null,
    val recipientDoctorId: Long? = null,
    val recipientPatientId: Long? = null,
    @field:Size(max = 10_000)
    val text: String? = null,
    @field:Size(max = 20)
    val type: String? = "text", // "text", "image", "voice", "document"
    @field:Size(max = 2048)
    val attachmentUrl: String? = null,
    @field:Size(max = 255)
    val attachmentName: String? = null,
    @field:Size(max = 2048)
    val thumbnailUrl: String? = null,
    val fileSize: Long? = null,
    val duration: Int? = null
)
