package com.shifa.security

import io.github.bucket4j.Bucket
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.cors.CorsUtils
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * SECURITY (NEW): Global rate limiting to mitigate DDoS and API abuse.
 * Applied per IP; auth endpoints use stricter limits than general API.
 * Returns 429 Too Many Requests and logs when limit exceeded.
 */
@Component
class RateLimitFilter(
    @Value("\${app.rate-limit.auth.requests-per-minute:10}") private val authLimitPerMin: Int,
    @Value("\${app.rate-limit.general.requests-per-minute:200}") private val generalLimitPerMin: Int
) : OncePerRequestFilter(), Ordered {

    private val log = LoggerFactory.getLogger(RateLimitFilter::class.java)

    override fun getOrder(): Int = -100 // Run before JWT/auth in security chain

    private val authBuckets = ConcurrentHashMap<String, Bucket>()
    private val generalBuckets = ConcurrentHashMap<String, Bucket>()

    private fun clientKey(request: HttpServletRequest): String {
        val xff = request.getHeader("X-Forwarded-For")
        return when {
            !xff.isNullOrBlank() -> xff.split(",").firstOrNull()?.trim() ?: request.remoteAddr
            else -> request.remoteAddr ?: "unknown"
        }
    }

    private fun isAuthPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return path.startsWith("/api/auth/")
    }

    private fun createBucket(requestsPerMin: Int): Bucket {
        val n = requestsPerMin.toLong()
        return Bucket.builder()
            .addLimit { it.capacity(n).refillGreedy(n, Duration.ofMinutes(1)) }
            .build()
    }

    private fun bucketFor(key: String, auth: Boolean): Bucket {
        val map = if (auth) authBuckets else generalBuckets
        val limit = if (auth) authLimitPerMin else generalLimitPerMin
        return map.getOrPut(key) { createBucket(limit) }
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        CorsUtils.isPreFlightRequest(request) ||
            request.method.equals("OPTIONS", ignoreCase = true)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val path = request.requestURI
        // Skip rate limiting for health/error/CORS preflight
        if (request.method.equals("OPTIONS", ignoreCase = true) ||
            path.startsWith("/actuator/health") ||
            path == "/error"
        ) {
            filterChain.doFilter(request, response)
            return
        }
        val key = clientKey(request)
        val auth = isAuthPath(path)
        val bucket = bucketFor(key, auth)

        if (!bucket.tryConsume(1)) {
            log.warn("Rate limit exceeded for key={} path={} authPath={}", key, path, auth)
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.contentType = "application/json"
            response.characterEncoding = "UTF-8"
            response.writer.write("""{"error":"Too many requests. Please try again later."}""")
            return
        }
        filterChain.doFilter(request, response)
    }
}
