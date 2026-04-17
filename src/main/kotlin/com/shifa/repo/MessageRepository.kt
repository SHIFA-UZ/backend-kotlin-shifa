package com.shifa.repo

import com.shifa.domain.Message
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface MessageRepository : JpaRepository<Message, Long> {
    @Query(
        """
        SELECT m FROM Message m
        LEFT JOIN FETCH m.senderUser
        WHERE m.conversation.id = :conversationId
        ORDER BY m.createdAt ASC
        """
    )
    fun findByConversationIdOrderByCreatedAtAsc(@Param("conversationId") conversationId: Long): List<Message>

    @Query(
        """
        SELECT COUNT(m) FROM Message m
        WHERE m.conversation.doctorUser.id = :doctorUserId
        AND m.isRead = false
        AND m.senderUser.id != :doctorUserId
        """
    )
    fun countUnreadMessagesForDoctor(@Param("doctorUserId") doctorUserId: Long): Long

    @Query(
        """
        SELECT COUNT(m) FROM Message m
        WHERE m.recipientPatient.id = :patientId
        AND m.isRead = false
        """
    )
    fun countUnreadMessagesForPatient(@Param("patientId") patientId: Long): Long

    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM Message m WHERE m.conversation.id IN :conversationIds")
    fun deleteByConversationIdIn(@Param("conversationIds") conversationIds: List<Long>): Int

    // Note: markConversationAsRead is handled in service layer with @Transactional
}
