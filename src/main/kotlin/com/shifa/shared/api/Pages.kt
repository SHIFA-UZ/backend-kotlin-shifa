package com.shifa.shared.api

import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable

/**
 * Paged siblings of the list endpoints (roadmap P6): the list endpoint keeps returning everything, its `/page` sibling
 * returns one page of the same rows in the same order, in the `Page` shape the admin endpoints already use. Paging
 * happens after the list is built, so the sibling bounds the response (what the apps hold in memory and transfer), not
 * the query; a `sort` parameter is ignored. Database-side paging follows per list when one is measured to be slow.
 */
object Pages {
    fun <T> of(all: List<T>, pageable: Pageable): Page<T> {
        if (pageable.isUnpaged) return PageImpl(all, pageable, all.size.toLong())
        val from = minOf(pageable.offset, all.size.toLong())
        val to = minOf(from + pageable.pageSize.toLong(), all.size.toLong())
        return PageImpl(all.subList(from.toInt(), to.toInt()), pageable, all.size.toLong())
    }
}
