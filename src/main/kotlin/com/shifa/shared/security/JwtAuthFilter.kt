package com.shifa.shared.security

import com.shifa.auth.infrastructure.UserSessionRepository
import com.shifa.shared.ErrorCode
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.OffsetDateTime

@Component
class JwtAuthFilter(
    private val userDetailsService: UserDetailsService,
    private val principalResolver: PrincipalResolverService,
    private val jwt: JwtService,
    private val userSessionRepository: UserSessionRepository,
    private val sessionValidationCache: UserSessionValidationCache,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(JwtAuthFilter::class.java)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        // Skip public auth endpoints
        val path = request.servletPath
        if (path == "/api/auth/login" || 
            path == "/api/auth/register" || 
            path == "/api/auth/register-clinic-staff" ||
            path == "/api/auth/register-patient" || 
            path == "/api/auth/verify-key" ||
            path == "/api/auth/check-existing-patient" ||
            path == "/api/auth/check-existing-doctor" ||
            path == "/api/auth/check-identifier" ||
            path == "/api/auth/create-patient-for-doctor" ||
            path == "/api/auth/send-email-otp" ||
            path == "/api/auth/admin/request-login-otp" ||
            path == "/api/auth/admin/verify-login-otp") {
            return true
        }

        // 🔴 IMPORTANT: skip CORS preflight
        if (request.method.equals("OPTIONS", ignoreCase = true)) return true

        // Actuator (liveness/readiness): never run JWT logic; matches SecurityConfig permitAll
        if (path.startsWith("/actuator/")) return true

        return false
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val header = request.getHeader("Authorization")

        if (header != null && header.startsWith("Bearer ")) {
            val token = header.substring(7)

            runCatching {
                val claims = jwt.parse(token)
                val userId = claims.subject
                val jti = claims.id
                if (jti.isNullOrBlank()) {
                    log.warn("JWT rejected: missing jti (session binding)")
                    response.status = HttpServletResponse.SC_UNAUTHORIZED
                    response.contentType = "application/json"
                    response.characterEncoding = "UTF-8"
                    response.writer.write(com.shifa.shared.ErrorBodies.filterJson("Session invalid", com.shifa.shared.ErrorCode.SESSION_INVALID))
                    return
                }
                val now = OffsetDateTime.now()
                val cached = sessionValidationCache.get(jti)
                if (cached != null) {
                    if (!sessionValidationCache.isCurrentlyValid(cached, now)) {
                        log.warn("JWT rejected: session revoked or expired for jti={}", jti.take(8))
                        response.status = HttpServletResponse.SC_UNAUTHORIZED
                        response.contentType = "application/json"
                        response.characterEncoding = "UTF-8"
                        response.writer.write(com.shifa.shared.ErrorBodies.filterJson("Session expired or signed out", com.shifa.shared.ErrorCode.SESSION_EXPIRED))
                        return
                    }
                } else {
                    val session = userSessionRepository.findByTokenJti(jti).orElse(null)
                    if (session == null || session.revoked || session.expiresAt.isBefore(now)) {
                        log.warn("JWT rejected: session revoked or expired for jti={}", jti.take(8))
                        response.status = HttpServletResponse.SC_UNAUTHORIZED
                        response.contentType = "application/json"
                        response.characterEncoding = "UTF-8"
                        response.writer.write(com.shifa.shared.ErrorBodies.filterJson("Session expired or signed out", com.shifa.shared.ErrorCode.SESSION_EXPIRED))
                        return
                    }
                    sessionValidationCache.putValid(jti, session.expiresAt)
                }

                // Use token's role claim for multi-role: doctor with PATIENT token gets PatientPrincipal
                val tokenRole = claims["role"]?.toString()
                val userIdLong = userId.toLongOrNull()
                    ?: run {
                        log.warn("JWT rejected: invalid subject")
                        response.status = HttpServletResponse.SC_UNAUTHORIZED
                        response.contentType = "application/json"
                        response.characterEncoding = "UTF-8"
                        response.writer.write(com.shifa.shared.ErrorBodies.filterJson("Invalid token", com.shifa.shared.ErrorCode.TOKEN_INVALID))
                        return
                    }
                val user = principalResolver.getPrincipalForToken(userIdLong, tokenRole)

                // Reject disabled accounts so admin disable takes effect immediately
                if (!user.isEnabled) {
                    log.warn("JWT rejected: account disabled for userId={}", userId)
                    response.status = HttpServletResponse.SC_FORBIDDEN
                    response.contentType = "application/json"
                    response.characterEncoding = "UTF-8"
                    response.writer.write(com.shifa.shared.ErrorBodies.filterJson("Account is disabled", com.shifa.shared.ErrorCode.ACCOUNT_DISABLED))
                    return
                }

                val authentication = UsernamePasswordAuthenticationToken(
                    user,
                    null,
                    user.authorities
                ).apply {
                    details = WebAuthenticationDetailsSource().buildDetails(request)
                }

                SecurityContextHolder.getContext().authentication = authentication
                // Only log at trace level in production to prevent memory issues
                log.trace("JWT Filter: Authentication set successfully for user: $userId with roles: ${user.authorities}")
            }.onFailure {
                // Only credential problems are swallowed; anything else (database down, bug) must surface as a 500.
                if (it !is JwtException && it !is IllegalArgumentException && it !is AuthenticationException) throw it
                // Unparsable, tampered or expired token: continue unauthenticated so public routes still work, and
                // tell ApiAuthenticationEntryPoint why, so a protected route answers 401 with the API body.
                log.warn("JWT validation failed for ${request.servletPath}: ${it.message}")
                request.setAttribute(
                    ApiAuthenticationEntryPoint.TOKEN_FAILURE_ATTRIBUTE,
                    if (it is ExpiredJwtException) ErrorCode.SESSION_EXPIRED else ErrorCode.TOKEN_INVALID,
                )
            }
        }
        // Removed debug logs for missing headers - too verbose and causes memory issues

        filterChain.doFilter(request, response)
    }
}
