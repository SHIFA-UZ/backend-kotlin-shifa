package com.shifa.chat.application

import com.shifa.chat.domain.Conversation
import com.shifa.chat.infrastructure.ConversationRepository
import org.springframework.stereotype.Service

/**
 * The feature's door to Conversation records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [ConversationRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class Conversations(private val repo: ConversationRepository) {
    fun findByPatientParticipantIdOrderByLastMessageDesc(patientId: Long): List<Conversation> = repo.findByPatientParticipantIdOrderByLastMessageDesc(patientId)
    fun findByDoctorUserIdOrDoctorParticipantId(userId: Long): List<Conversation> = repo.findByDoctorUserIdOrDoctorParticipantId(userId)
    fun deleteAll(entities: Iterable<Conversation>) = repo.deleteAll(entities)
    fun deleteAll() = repo.deleteAll()
}
