package com.shifa.security

import com.shifa.repo.UserSessionRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
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
    private val userSessionRepository: UserSessionRepository
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
                    response.writer.write("""{"error":"Session invalid"}""")
                    return
                }
                val session = userSessionRepository.findByTokenJti(jti).orElse(null)
                if (session == null || session.revoked || session.expiresAt.isBefore(OffsetDateTime.now())) {
                    log.warn("JWT rejected: session revoked or expired for jti={}", jti.take(8))
                    response.status = HttpServletResponse.SC_UNAUTHORIZED
                    response.contentType = "application/json"
                    response.characterEncoding = "UTF-8"
                    response.writer.write("""{"error":"Session expired or signed out"}""")
                    return
                }

                // Use token's role claim for multi-role: doctor with PATIENT token gets PatientPrincipal
                val tokenRole = claims["role"]?.toString()
                val userIdLong = userId.toLongOrNull()
                    ?: run {
                        log.warn("JWT rejected: invalid subject")
                        response.status = HttpServletResponse.SC_UNAUTHORIZED
                        response.contentType = "application/json"
                        response.characterEncoding = "UTF-8"
                        response.writer.write("""{"error":"Invalid token"}""")
                        return
                    }
                val user = principalResolver.getPrincipalForToken(userIdLong, tokenRole)

                // Reject disabled accounts so admin disable takes effect immediately
                if (!user.isEnabled) {
                    log.warn("JWT rejected: account disabled for userId={}", userId)
                    response.status = HttpServletResponse.SC_FORBIDDEN
                    response.contentType = "application/json"
                    response.characterEncoding = "UTF-8"
                    response.writer.write("""{"error":"Account is disabled"}""")
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
                log.warn("JWT validation failed for ${request.servletPath}: ${it.message}", it)
            }
        }
        // Removed debug logs for missing headers - too verbose and causes memory issues

        filterChain.doFilter(request, response)
    }
}
