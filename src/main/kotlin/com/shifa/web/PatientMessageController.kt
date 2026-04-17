package com.shifa.web

import com.shifa.repo.PatientProfileRepository
import com.shifa.security.PatientPrincipal
import com.shifa.service.MessageService
import com.shifa.web.dto.ConversationDto
import com.shifa.web.dto.ConversationWithMessagesDto
import com.shifa.web.dto.MessageDto
import com.shifa.web.dto.SendMessageRequest
import com.shifa.web.dto.UserSearchResultDto
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/patients/me/messages")
class PatientMessageController(
    private val messageService: MessageService,
    private val patientProfiles: PatientProfileRepository
) {

    private fun currentPatientProfileId(principal: PatientPrincipal): Long {
        val user = principal.user
        val profile = user.phone?.let { patientProfiles.findByPhone(it) }
            ?.orElseGet {
                user.email?.let { patientProfiles.findByEmail(it) }
                    ?.orElse(null)
            }
            ?: throw org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "Patient profile not found for user ${user.id}"
            )

        return profile.id
            ?: throw org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "Patient profile ID not found"
            )
    }

    @GetMapping("/conversations")
    fun listConversations(
        @AuthenticationPrincipal principal: PatientPrincipal
    ): ResponseEntity<List<ConversationDto>> {
        val patientProfileId = currentPatientProfileId(principal)
        val conversations = messageService.listConversationsForPatient(
            patientProfileId = patientProfileId,
            patientUserId = principal.user.id
        )
        return ResponseEntity.ok(conversations)
    }

    @GetMapping("/conversations/{conversationId}")
    fun getConversation(
        @PathVariable conversationId: Long,
        @AuthenticationPrincipal principal: PatientPrincipal
    ): ResponseEntity<ConversationWithMessagesDto> {
        val patientProfileId = currentPatientProfileId(principal)
        val result = messageService.getConversationWithMessagesForPatient(
            conversationId = conversationId,
            patientProfileId = patientProfileId,
            patientUserId = principal.user.id
        )
        return ResponseEntity.ok(result)
    }

    @PostMapping("/send")
    fun sendMessage(
        @RequestBody @Valid request: SendMessageRequest,
        @AuthenticationPrincipal principal: PatientPrincipal
    ): ResponseEntity<MessageDto> {
        val patientProfileId = currentPatientProfileId(principal)
        val message = messageService.sendMessageAsPatient(
            patientUserId = principal.user.id,
            patientProfileId = patientProfileId,
            request = request
        )
        return ResponseEntity.ok(message)
    }

    @PostMapping("/conversations/{conversationId}/read")
    fun markAsRead(
        @PathVariable conversationId: Long,
        @AuthenticationPrincipal principal: PatientPrincipal
    ): ResponseEntity<Void> {
        val patientProfileId = currentPatientProfileId(principal)
        messageService.markAsReadForPatient(conversationId, patientProfileId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/search")
    fun searchDoctors(
        @RequestParam q: String
    ): ResponseEntity<List<UserSearchResultDto>> {
        val results = messageService.searchDoctorsForPatient(q)
        return ResponseEntity.ok(results)
    }

    @GetMapping("/unread-count")
    fun getUnreadCount(
        @AuthenticationPrincipal principal: PatientPrincipal
    ): ResponseEntity<Map<String, Long>> {
        val patientProfileId = currentPatientProfileId(principal)
        val count = messageService.getUnreadCountForPatient(patientProfileId)
        return ResponseEntity.ok(mapOf("count" to count))
    }
}
