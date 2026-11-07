package com.shifa.chat.application

import com.shifa.chat.domain.Message
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class MessageCursorTest {
    private fun message(id: Long): Message = mock(Message::class.java).also { `when`(it.id).thenReturn(id) }

    private val all = listOf(3L, 1L, 2L, 5L, 4L).map(::message)

    @Test
    fun `without after every message comes back as loaded`() {
        assertEquals(all, MessageCursor.page(all, null, 1))
    }

    @Test
    fun `after keeps the newer messages oldest first up to the limit`() {
        assertEquals(listOf(3L, 4L), MessageCursor.page(all, 2L, 2).map { it.id })
        assertEquals(listOf(3L, 4L, 5L), MessageCursor.page(all, 2L, null).map { it.id })
        assertEquals(emptyList<Long>(), MessageCursor.page(all, 5L, null).map { it.id })
    }

    @Test
    fun `the limit is clamped to 1 to 200`() {
        val many = (1L..300L).map(::message)
        assertEquals(200, MessageCursor.page(many, 0L, 1000).size)
        assertEquals(100, MessageCursor.page(many, 0L, null).size)
        assertEquals(1, MessageCursor.page(many, 0L, 0).size)
    }
}
