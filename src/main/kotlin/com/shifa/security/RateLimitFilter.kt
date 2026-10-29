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
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory

/**
 * SECURITY (NEW): Global rate limiting to mitigate DDoS and API abuse.
 * Applied per IP; auth endpoints use stricter limits than general API.
 * Returns 429 Too Many Requests and logs when limit exceeded.
 */
@Component
class RateLimitFilter(
    @Value("\${app.rate-limit.auth.requests-per-minute:10}") private val authLimitPerMin: Int,
    @Value("\${app.rate-limit.general.requests-per-minute:200}") private val generalLimitPerMin: Int,
    @Value("\${app.rate-limit.trusted-proxy-hops:0}") private val trustedProxyHops: Int,
) : OncePerRequestFilter(), Ordered {

    companion object {
        /**
         * Rate-limit key for a request. With [trustedProxyHops] = 0 the first `X-Forwarded-For` entry is used
         * (legacy; the client controls it). With n > 0 the n-th entry from the end is used: that is the one
         * appended by the n-th trusted proxy in front of us (Railway's edge = 1), which the client cannot forge.
         */
        fun clientKey(forwardedFor: String?, remoteAddr: String?, trustedProxyHops: Int): String {
            if (forwardedFor.isNullOrBlank()) return remoteAddr ?: "unknown"
            // Positions are kept as sent (blank entries included), so a forged blank cannot shift the trusted slot.
            val entries = forwardedFor.split(',').map { it.trim() }
            if (trustedProxyHops <= 0) return entries.first() // legacy, byte-identical to the previous code
            return entries.getOrNull(entries.size - trustedProxyHops)?.takeIf { it.isNotEmpty() }
                ?: remoteAddr ?: "unknown"
        }
    }

    private val log = LoggerFactory.getLogger(RateLimitFilter::class.java)

    override fun getOrder(): Int = -100 // Run before JWT/auth in security chain

    private val authBuckets = idleEvictingBuckets()
    private val generalBuckets = idleEvictingBuckets()

    private fun clientKey(request: HttpServletRequest): String =
        clientKey(request.getHeader("X-Forwarded-For"), request.remoteAddr, trustedProxyHops)

    private fun isAuthPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return path.startsWith("/api/auth/")
    }

    /** Buckets of clients idle for an hour are dropped, so the map cannot grow without bound (roadmap 2.6). */
    private fun idleEvictingBuckets(): Cache<String, Bucket> =
        Caffeine.newBuilder().expireAfterAccess(Duration.ofHours(1)).maximumSize(100_000).build()

    private fun createBucket(requestsPerMin: Int): Bucket {
        val n = requestsPerMin.toLong()
        return Bucket.builder()
            .addLimit { it.capacity(n).refillGreedy(n, Duration.ofMinutes(1)) }
            .build()
    }

    private fun bucketFor(key: String, auth: Boolean): Bucket {
        val map = if (auth) authBuckets else generalBuckets
        val limit = if (auth) authLimitPerMin else generalLimitPerMin
        return map.get(key) { createBucket(limit) }
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
            if (log.isDebugEnabled) {
                // DEBUG only (enabled in application-dev.yml): the right-hand end of the X-Forwarded-For chain
                // shows which position trusted-proxy-hops must point at. Bounded and stripped of control characters.
                val raw = request.getHeader("X-Forwarded-For").orEmpty()
                val tail = raw.takeLast(256).map { if (it.isISOControl()) '?' else it }.joinToString("")
                log.debug("forwardedFor={}{}", if (raw.length > 256) "[truncated]" else "", tail)
            }
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.contentType = "application/json"
            response.characterEncoding = "UTF-8"
            response.writer.write("""{"error":"Too many requests. Please try again later."}""")
            return
        }
        filterChain.doFilter(request, response)
    }
}
