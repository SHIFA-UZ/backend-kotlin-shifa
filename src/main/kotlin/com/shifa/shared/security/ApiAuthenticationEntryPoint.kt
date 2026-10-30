package com.shifa.shared.security

import com.shifa.shared.ErrorBodies
import com.shifa.shared.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component

/**
 * Answers an unauthenticated request to a protected route with 401 and the API error body, so the apps sign out
 * (they treat 401 as "session invalid"). Without it Spring falls back to a 403 with its default body.
 *
 * [JwtAuthFilter] records why a bearer token was rejected in [TOKEN_FAILURE_ATTRIBUTE]; a request without a token gets
 * [ErrorCode.UNAUTHORIZED]. Public routes are unaffected: Spring only calls an entry point when authorization fails.
 */
@Component
class ApiAuthenticationEntryPoint : AuthenticationEntryPoint {

    override fun commence(request: HttpServletRequest, response: HttpServletResponse, authException: AuthenticationException) {
        val code = request.getAttribute(TOKEN_FAILURE_ATTRIBUTE) as? ErrorCode ?: ErrorCode.UNAUTHORIZED
        val message = when (code) {
            ErrorCode.SESSION_EXPIRED -> "Session expired or signed out"
            ErrorCode.TOKEN_INVALID -> "Invalid token"
            else -> "Authentication required"
        }
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        response.writer.write(ErrorBodies.filterJson(message, code))
    }

    companion object {
        const val TOKEN_FAILURE_ATTRIBUTE = "com.shifa.tokenFailure"
    }
}
