package com.shifa.chat.application

/**
 * The `after`/`limit` cursor of a conversation read: without [after] every message as before (oldest first, [limit]
 * ignored); with it only the messages whose id is larger, by id, at most [limit] (default 100, at most 200).
 */
object MessageCursor {
    const val DEFAULT_LIMIT = 100
    const val MAX_LIMIT = 200

    /** How many messages a read with `after` returns at most. */
    fun size(limit: Int?): Int = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
}
