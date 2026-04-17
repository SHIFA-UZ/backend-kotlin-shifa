package com.shifa.security

import io.github.bucket4j.Bucket
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * SECURITY (NEW): Per-user rate limiting for authenticated requests.
 * Applied in addition to IP-based RateLimitFilter to limit abuse by logged-in users.
 * Uses same general limit (e.g. 200 req/min) per user; auth endpoints remain IP-only (no JWT yet).
 */
@Component
class UserRateLimitFilter(
    @Value("\${app.rate-limit.general.requests-per-minute:200}") private val generalLimitPerMin: Int
) : OncePerRequestFilter(), Ordered {

    override fun getOrder(): Int = 100 // After JwtAuthFilter so SecurityContext is populated

    private val log = LoggerFactory.getLogger(UserRateLimitFilter::class.java)
    private val userBuckets = ConcurrentHashMap<String, Bucket>()

    private fun createBucket(requestsPerMin: Int): Bucket {
        val n = requestsPerMin.toLong()
        return Bucket.builder()
            .addLimit { it.capacity(n).refillGreedy(n, Duration.ofMinutes(1)) }
            .build()
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val auth = SecurityContextHolder.getContext().authentication
        if (auth == null || !auth.isAuthenticated || auth.principal !is UserDetails) {
            // Not authenticated; IP limit only (handled by RateLimitFilter)
            filterChain.doFilter(request, response)
            return
        }
        val userId = (auth.principal as UserDetails).username
        val key = "user:$userId"
        val bucket = userBuckets.getOrPut(key) { createBucket(generalLimitPerMin) }

        if (!bucket.tryConsume(1)) {
            log.warn("User rate limit exceeded for userId={} path={}", userId, request.requestURI)
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.contentType = "application/json"
            response.characterEncoding = "UTF-8"
            response.writer.write("""{"error":"Too many requests. Please try again later."}""")
            return
        }
        filterChain.doFilter(request, response)
    }
}
