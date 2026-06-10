package com.shifa.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Answers CORS preflight immediately so OPTIONS does not wait behind blocked request threads
 * (e.g. SMTP during login OTP). Railway proxy times out at ~300s when no response is sent.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorsPreflightFilter(
    private val corsConfigurationSource: CorsConfigurationSource,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!request.method.equals("OPTIONS", ignoreCase = true)) {
            filterChain.doFilter(request, response)
            return
        }

        val config = corsConfigurationSource.getCorsConfiguration(request)
        if (config != null) {
            val corsProcessor = org.springframework.web.cors.DefaultCorsProcessor()
            val handled = corsProcessor.processRequest(config, request, response)
            if (handled && !response.isCommitted) {
                response.status = HttpServletResponse.SC_OK
                return
            }
        }
        filterChain.doFilter(request, response)
    }
}
