package com.shifa.shared.security

import com.shifa.auth.infrastructure.UserSessionRepository
import com.shifa.shared.ErrorCode
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.InsufficientAuthenticationException
import org.springframework.security.core.userdetails.UserDetailsService
import java.util.Optional

/**
 * A bearer token that does not verify must not authenticate, must let the request continue (public routes keep
 * working), and must leave the reason for [ApiAuthenticationEntryPoint], which answers 401 with the API body.
 */
class JwtAuthFilterTest {

    private val secret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val sessions: UserSessionRepository = mock(UserSessionRepository::class.java)

    private fun filter(jwt: JwtService) = JwtAuthFilter(
        mock(UserDetailsService::class.java),
        mock(PrincipalResolverService::class.java),
        jwt,
        sessions,
        mock(UserSessionValidationCache::class.java),
    )

    private fun request(token: String) = MockHttpServletRequest("GET", "/api/patients/me/profile").apply {
        servletPath = "/api/patients/me/profile"
        addHeader("Authorization", "Bearer $token")
    }

    private fun failureCode(jwt: JwtService, token: String): ErrorCode? {
        val request = request(token)
        val chain = MockFilterChain()
        filter(jwt).doFilter(request, MockHttpServletResponse(), chain)
        assertEquals(request, chain.request, "the request must continue down the chain")
        return request.getAttribute(ApiAuthenticationEntryPoint.TOKEN_FAILURE_ATTRIBUTE) as ErrorCode?
    }

    @Test
    fun `token signed with another secret is marked TOKEN_INVALID`() {
        val token = JwtService(secret.reversed(), "shifa", 60, 60).generate(7, "p@x.dev", "PATIENT").token
        assertEquals(ErrorCode.TOKEN_INVALID, failureCode(JwtService(secret, "shifa", 60, 60), token))
    }

    @Test
    fun `garbage token is marked TOKEN_INVALID`() {
        assertEquals(ErrorCode.TOKEN_INVALID, failureCode(JwtService(secret, "shifa", 60, 60), "not.a.jwt"))
    }

    @Test
    fun `expired token is marked SESSION_EXPIRED`() {
        val jwt = JwtService(secret, "shifa", -5, -5)
        assertEquals(ErrorCode.SESSION_EXPIRED, failureCode(jwt, jwt.generate(7, "p@x.dev", "PATIENT").token))
    }

    @Test
    fun `an infrastructure failure is not turned into a credential error`() {
        val jwt = JwtService(secret, "shifa", 60, 60)
        `when`(sessions.findByTokenJti(org.mockito.ArgumentMatchers.anyString())).thenThrow(IllegalStateException("db down"))
        val request = request(jwt.generate(7, "p@x.dev", "PATIENT").token)

        assertThrows(IllegalStateException::class.java) {
            filter(jwt).doFilter(request, MockHttpServletResponse(), MockFilterChain())
        }
        assertNull(request.getAttribute(ApiAuthenticationEntryPoint.TOKEN_FAILURE_ATTRIBUTE))
    }

    @Test
    fun `entry point writes the recorded code, or UNAUTHORIZED without a token`() {
        val marked = MockHttpServletRequest().apply {
            setAttribute(ApiAuthenticationEntryPoint.TOKEN_FAILURE_ATTRIBUTE, ErrorCode.SESSION_EXPIRED)
        }
        val response = MockHttpServletResponse()
        ApiAuthenticationEntryPoint().commence(marked, response, InsufficientAuthenticationException("x"))
        assertEquals(401, response.status)
        assertEquals("application/json;charset=UTF-8", response.contentType)
        assertTrue(response.contentAsString.contains("\"code\":\"SESSION_EXPIRED\""), response.contentAsString)

        val anonymous = MockHttpServletResponse()
        ApiAuthenticationEntryPoint().commence(MockHttpServletRequest(), anonymous, InsufficientAuthenticationException("x"))
        assertEquals(401, anonymous.status)
        assertTrue(anonymous.contentAsString.contains("\"code\":\"UNAUTHORIZED\""), anonymous.contentAsString)
    }
}
