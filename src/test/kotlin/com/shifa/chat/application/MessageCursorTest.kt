package com.shifa.chat.application

import com.shifa.auth.domain.User
import com.shifa.chat.domain.Conversation
import com.shifa.chat.domain.Message
import com.shifa.chat.infrastructure.ConversationRepository
import com.shifa.chat.infrastructure.MessageRepository
import com.shifa.patients.domain.PatientProfile
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageRequest

class MessageCursorTest {

    @Test
    fun `the limit is clamped to 1 to 200`() {
        assertEquals(200, MessageCursor.size(1000))
        assertEquals(100, MessageCursor.size(null))
        assertEquals(1, MessageCursor.size(0))
    }

    private val doctorUser = User(id = 2, passwordHash = "x")
    private val patientUser = User(id = 3, passwordHash = "x")
    private val patient = mock(PatientProfile::class.java).also {
        `when`(it.id).thenReturn(7L)
        `when`(it.fullName).thenReturn("Franco")
    }
    private val conversation = Conversation(id = 5, doctorUser = doctorUser, patientParticipant = patient)

    private fun message(id: Long, from: User, read: Boolean) =
        Message(id = id, conversation = conversation, senderUser = from, text = "m$id", isRead = read)

    /** Unread for the doctor: 3 and 4 (the patient's, not read). */
    private val all = listOf(
        message(1, patientUser, read = true),
        message(2, doctorUser, read = false),
        message(3, patientUser, read = false),
        message(4, patientUser, read = false),
    )

    private val conversations = mock(ConversationRepository::class.java).also {
        `when`(it.findById(5L)).thenReturn(Optional.of(conversation))
    }
    private val messages = mock(MessageRepository::class.java).also {
        `when`(it.findByConversationIdOrderByCreatedAtAsc(5L)).thenReturn(all)
        `when`(it.findByConversationIdAfter(5L, 2L, PageRequest.of(0, 1))).thenReturn(listOf(all[2]))
        `when`(it.findLastMessagePerConversation(listOf(5L))).thenReturn(listOf(arrayOf<Any>(5L, all[3])))
        `when`(it.countUnreadPerConversationForUser(listOf(5L), 2L)).thenReturn(listOf(arrayOf<Any>(5L, 2L)))
    }
    private val service = MessageService(
        conversations,
        messages,
        mock(com.shifa.notifications.application.NotificationService::class.java),
        mock(com.shifa.auth.application.Users::class.java),
        mock(com.shifa.doctors.application.DoctorProfiles::class.java),
        mock(com.shifa.patients.application.PatientProfiles::class.java),
        mock(com.shifa.shared.security.FileUrlSigner::class.java),
        mock(com.shifa.patients.application.PatientProfileMapper::class.java),
        mock(com.shifa.shared.PublicUrls::class.java),
    )

    @Test
    fun `without after every message comes back and the cursor query is not used`() {
        val read = service.getConversationWithMessages(5L, 2L, after = null, limit = 1)

        assertEquals(listOf(1L, 2L, 3L, 4L), read.messages.map { it.id })
        assertEquals(2, read.conversation.unreadCount)
        assertEquals("m4", read.conversation.lastMessage)
        verify(messages, never()).findByConversationIdAfter(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any() ?: PageRequest.of(0, 1))
    }

    @Test
    fun `with after only the page is read, and the conversation fields are the same as for a full read`() {
        val full = service.getConversationWithMessages(5L, 2L).conversation
        val read = service.getConversationWithMessages(5L, 2L, after = 2L, limit = 1)

        assertEquals(listOf(3L), read.messages.map { it.id })
        assertEquals(full, read.conversation)
        verify(messages).findByConversationIdAfter(5L, 2L, PageRequest.of(0, 1))
    }
}
