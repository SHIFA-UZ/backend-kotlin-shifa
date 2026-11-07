package com.shifa.chat.application

import com.shifa.chat.domain.Message

/**
 * The `after`/`limit` cursor of a conversation read: without [after] every message as before (oldest first, [limit]
 * ignored); with it only the messages whose id is larger, by id, at most [limit] (default 100, at most 200).
 */
object MessageCursor {
    const val DEFAULT_LIMIT = 100
    const val MAX_LIMIT = 200

    // ponytail: filters the loaded conversation (the conversation fields still need every message); a keyset query
    // with LIMIT when conversations get long.
    fun page(messages: List<Message>, after: Long?, limit: Int?): List<Message> {
        if (after == null) return messages
        val size = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        return messages.filter { (it.id ?: 0L) > after }.sortedBy { it.id }.take(size)
    }
}
