package com.shifa.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.cors.CorsUtils
import org.springframework.web.filter.OncePerRequestFilter

/**
 * SECURITY (NEW): Add security headers to all responses and enforce HTTPS in production.
 * - X-Frame-Options, X-Content-Type-Options, Referrer-Policy, Content-Security-Policy
 * - In production: redirect HTTP to HTTPS when X-Forwarded-Proto is http (e.g. behind Railway).
 */
@Component
class SecurityHeadersFilter(
    @Value("\${spring.profiles.active:}") private val activeProfile: String
) : OncePerRequestFilter(), Ordered {

    override fun getOrder(): Int = -200 // Run first in security chain

    private val isProduction: Boolean
        get() = activeProfile.contains("prod")

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        CorsUtils.isPreFlightRequest(request)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        // SECURITY (NEW): HTTPS enforcement in production — only when the edge says the client used HTTP.
        // Railway/platform health checks often hit the container directly with no X-Forwarded-Proto; do not
        // redirect those (and never NPE on a missing header). Actuator must stay reachable for orchestration.
        val path = request.requestURI ?: ""
        if (isProduction && !path.startsWith("/actuator/")) {
            val proto = request.getHeader("X-Forwarded-Proto")
            if (proto != null && proto.equals("http", ignoreCase = true)) {
                val redirectUrl = "https://${request.serverName}${path}" +
                    if (request.queryString != null) "?${request.queryString}" else ""
                response.sendRedirect(redirectUrl)
                return
            }
        }

        // SECURITY (NEW): Prevent clickjacking and MIME sniffing
        response.setHeader("X-Frame-Options", "DENY")
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin")
        // CSP: allow same-origin and common API usage; restrict inline scripts
        response.setHeader(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self'; connect-src 'self' https:; frame-ancestors 'none'; base-uri 'self'"
        )

        filterChain.doFilter(request, response)
    }
}
