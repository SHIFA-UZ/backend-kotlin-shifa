package com.shifa.shared

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * One id per request: taken from a well-formed `X-Request-Id` header or generated. It is returned in the
 * same response header and put in the MDC, so a client's error can be matched to log lines without PII.
 */
class RequestIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val id = requestId(request.getHeader(HEADER))
        response.setHeader(HEADER, id)
        MDC.put(MDC_KEY, id)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val HEADER = "X-Request-Id"
        const val MDC_KEY = "requestId"
        private val WELL_FORMED = Regex("[A-Za-z0-9_-]{8,64}")

        /** The client's id when it is a plain token (no log injection, bounded), otherwise a fresh UUID. */
        fun requestId(header: String?): String = header?.takeIf { WELL_FORMED.matches(it) } ?: UUID.randomUUID().toString()
    }
}
