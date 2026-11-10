package com.shifa.shared.util

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant

/** The time window and sort rules shared by the admin log lists (audit log, user activity log). */
object LogSearch {
    val DEFAULT_WINDOW: Duration = Duration.ofDays(30)

    /**
     * The window to read, `from` inclusive and `to` exclusive. A missing `from` becomes [DEFAULT_WINDOW] before
     * `to` (or now), unless [unboundedWhenNoFrom]: the caller has a selective indexed filter and reads all time.
     */
    fun window(from: Instant?, to: Instant?, unboundedWhenNoFrom: Boolean, now: Instant = Instant.now()): Pair<Instant?, Instant?> {
        if (from != null && to != null && !from.isBefore(to)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to")
        }
        val start = from ?: if (unboundedWhenNoFrom) null else (to ?: now).minus(DEFAULT_WINDOW)
        return start to to
    }

    /**
     * The page with only the sort keys in [allowed] (API name to entity path); anything else is dropped, as old
     * clients send sorts that were always ignored. No usable key means newest first.
     */
    fun pageable(pageable: Pageable, allowed: Map<String, String>): Pageable {
        val orders = pageable.sort.mapNotNull { order -> allowed[order.property]?.let { Sort.Order(order.direction, it) } }
        val sort = if (orders.isEmpty()) Sort.by(Sort.Direction.DESC, "createdAt") else Sort.by(orders).and(Sort.by(Sort.Direction.DESC, "id"))
        return PageRequest.of(pageable.pageNumber, pageable.pageSize, sort)
    }
}
