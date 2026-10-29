package com.shifa.shared.api

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping

/**
 * One JSON line per API request: method, route template, status, milliseconds. No query string, no ids,
 * no user data, so the line is safe to keep in logs and cheap to aggregate (scripts/latency-report.sh).
 * Volume: one INFO line per request. To silence it in an environment, set
 * logging.level.com.shifa.shared.api.RequestTimingFilter: WARN.
 */
class RequestTimingFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(RequestTimingFilter::class.java)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val started = System.nanoTime()
        try {
            chain.doFilter(request, response)
        } finally {
            val pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String
            val ms = (System.nanoTime() - started) / 1_000_000
            log.info(line(request.method, routeOf(pattern, request.requestURI), response.status, ms))
        }
    }

    companion object {
        /** Fixed controller areas (the first segment of every @RequestMapping under /api). Not user-supplied. */
        private val API_AREAS = setOf("admin", "ai", "appointments", "auth", "calendar", "care-partnerships", "clinical-engine", "clinics", "consultations", "doctor", "doctors", "document-access-requests", "icd", "messages", "notifications", "patients", "payments", "practice", "profile", "prophylaxis", "public", "schedule", "tasks", "treatment-plans", "video", "webhooks")
        private val API_AREA = Regex("^/api/([a-z][a-z-]*)(/|$)")

        /**
         * The matched route template. When none was matched (rejected before dispatch, unknown URL) only a
         * known controller area is kept (e.g. the auth area becomes "/api/auth/" plus an asterisk): raw path
         * segments can carry emails, phones or tokens, so anything else is "<unmatched>".
         */
        fun routeOf(pattern: String?, path: String): String {
            if (pattern != null) return pattern
            val area = API_AREA.find(path)?.groupValues?.get(1)
            return if (area in API_AREAS) "/api/$area/*" else "<unmatched>"
        }

        fun line(method: String, route: String, status: Int, ms: Long): String =
            """{"evt":"http","method":"$method","route":"${route.replace("\"", "'")}","status":$status,"ms":$ms}"""
    }
}
