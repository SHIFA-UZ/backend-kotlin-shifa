package com.shifa.web

import com.shifa.security.ClinicStaffPrincipal
import com.shifa.security.DoctorPrincipal
import com.shifa.service.MessageService
import com.shifa.web.dto.*
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/messages")
class MessageController(
    private val messageService: MessageService
) {

    private fun resolveActorUserId(principal: Any): Long = when (principal) {
        is DoctorPrincipal -> principal.profile.user.id
        is ClinicStaffPrincipal -> principal.user.id
        else -> throw ResponseStatusException(HttpStatus.FORBIDDEN)
    }

    @GetMapping("/conversations")
    fun listConversations(
        @AuthenticationPrincipal principal: Any
    ): ResponseEntity<List<ConversationDto>> {
        val conversations = messageService.listConversations(resolveActorUserId(principal))
        return ResponseEntity.ok(conversations)
    }

    @GetMapping("/conversations/{conversationId}")
    fun getConversation(
        @PathVariable conversationId: Long,
        @AuthenticationPrincipal principal: Any
    ): ResponseEntity<ConversationWithMessagesDto> {
        val result = messageService.getConversationWithMessages(
            conversationId,
            resolveActorUserId(principal),
        )
        return ResponseEntity.ok(result)
    }

    /**
     * Start or get an existing conversation with a recipient without sending a message.
     */
    @PostMapping("/conversations/start")
    fun startConversation(
        @RequestBody @Valid request: StartConversationRequest,
        @AuthenticationPrincipal principal: Any
    ): ResponseEntity<ConversationDto> {
        val conversation = messageService.startOrGetConversation(
            doctorUserId = resolveActorUserId(principal),
            recipientDoctorId = request.recipientDoctorId,
            recipientPatientId = request.recipientPatientId
        )
        return ResponseEntity.ok(conversation)
    }

    @PostMapping("/send")
    fun sendMessage(
        @RequestBody @Valid request: SendMessageRequest,
        @AuthenticationPrincipal principal: Any
    ): ResponseEntity<MessageDto> {
        val message = messageService.sendMessage(resolveActorUserId(principal), request)
        return ResponseEntity.ok(message)
    }

    @PostMapping("/conversations/{conversationId}/read")
    fun markAsRead(
        @PathVariable conversationId: Long,
        @AuthenticationPrincipal principal: Any
    ): ResponseEntity<Void> {
        messageService.markAsRead(conversationId, resolveActorUserId(principal))
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/search")
    fun searchUsers(
        @RequestParam q: String,
        @AuthenticationPrincipal principal: Any
    ): ResponseEntity<List<UserSearchResultDto>> {
        val results = messageService.searchUsers(resolveActorUserId(principal), q)
        return ResponseEntity.ok(results)
    }

    @GetMapping("/unread-count")
    fun getUnreadCount(
        @AuthenticationPrincipal principal: Any
    ): ResponseEntity<Map<String, Long>> {
        val count = messageService.getUnreadCount(resolveActorUserId(principal))
        return ResponseEntity.ok(mapOf("count" to count))
    }
}
