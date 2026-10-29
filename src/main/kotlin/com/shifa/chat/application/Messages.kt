package com.shifa.chat.application

import com.shifa.chat.domain.Message
import com.shifa.chat.infrastructure.MessageRepository
import org.springframework.stereotype.Service

/**
 * The feature's door to Message records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [MessageRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class Messages(private val repo: MessageRepository) {
    fun findByConversationIdOrderByCreatedAtAsc(conversationId: Long): List<Message> = repo.findByConversationIdOrderByCreatedAtAsc(conversationId)
    fun deleteByConversationIdIn(conversationIds: List<Long>): Int = repo.deleteByConversationIdIn(conversationIds)
}
