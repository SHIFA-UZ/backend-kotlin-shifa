package com.shifa.web

import com.shifa.security.DoctorPrincipal
import com.shifa.service.MessageService
import com.shifa.web.dto.*
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/messages")
class MessageController(
    private val messageService: MessageService
) {

    @GetMapping("/conversations")
    fun listConversations(
        @AuthenticationPrincipal principal: DoctorPrincipal
    ): ResponseEntity<List<ConversationDto>> {
        val conversations = messageService.listConversations(principal.profile.user.id)
        return ResponseEntity.ok(conversations)
    }

    @GetMapping("/conversations/{conversationId}")
    fun getConversation(
        @PathVariable conversationId: Long,
        @AuthenticationPrincipal principal: DoctorPrincipal
    ): ResponseEntity<ConversationWithMessagesDto> {
        val result = messageService.getConversationWithMessages(conversationId, principal.profile.user.id)
        return ResponseEntity.ok(result)
    }

    /**
     * Start or get an existing conversation with a recipient without sending a message.
     */
    @PostMapping("/conversations/start")
    fun startConversation(
        @RequestBody @Valid request: StartConversationRequest,
        @AuthenticationPrincipal principal: DoctorPrincipal
    ): ResponseEntity<ConversationDto> {
        val conversation = messageService.startOrGetConversation(
            doctorUserId = principal.profile.user.id,
            recipientDoctorId = request.recipientDoctorId,
            recipientPatientId = request.recipientPatientId
        )
        return ResponseEntity.ok(conversation)
    }

    @PostMapping("/send")
    fun sendMessage(
        @RequestBody @Valid request: SendMessageRequest,
        @AuthenticationPrincipal principal: DoctorPrincipal
    ): ResponseEntity<MessageDto> {
        val message = messageService.sendMessage(principal.profile.user.id, request)
        return ResponseEntity.ok(message)
    }

    @PostMapping("/conversations/{conversationId}/read")
    fun markAsRead(
        @PathVariable conversationId: Long,
        @AuthenticationPrincipal principal: DoctorPrincipal
    ): ResponseEntity<Void> {
        messageService.markAsRead(conversationId, principal.profile.user.id)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/search")
    fun searchUsers(
        @RequestParam q: String,
        @AuthenticationPrincipal principal: DoctorPrincipal
    ): ResponseEntity<List<UserSearchResultDto>> {
        val results = messageService.searchUsers(principal.profile.user.id, q)
        return ResponseEntity.ok(results)
    }

    @GetMapping("/unread-count")
    fun getUnreadCount(
        @AuthenticationPrincipal principal: DoctorPrincipal
    ): ResponseEntity<Map<String, Long>> {
        val count = messageService.getUnreadCount(principal.profile.user.id)
        return ResponseEntity.ok(mapOf("count" to count))
    }
}
