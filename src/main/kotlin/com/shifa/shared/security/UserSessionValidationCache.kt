package com.shifa.shared.security

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Short-lived in-memory cache of JWT session validity by `jti`.
 * Cuts repeated DB hits on [JwtAuthFilter] for the same token.
 *
 * Every revoke must call [markRevoked] once it is committed, so it is visible within this JVM at once.
 * Multi-replica deploys need a shared store (e.g. Redis) for instant cross-instance revoke.
 */
@Component
class UserSessionValidationCache {

    data class Entry(
        val expiresAt: OffsetDateTime,
        val revoked: Boolean,
    )

    private val cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(30)) // a revoke on another instance is visible within 30 s (roadmap 2.6)
        .maximumSize(50_000)
        .build<String, Entry>()

    fun get(jti: String): Entry? = cache.getIfPresent(jti)

    /** Never replaces a revoked entry: a request that read the session just before its revoke cannot bring it back. */
    fun putValid(jti: String, expiresAt: OffsetDateTime) {
        cache.asMap().compute(jti) { _, old -> old?.takeIf { it.revoked } ?: Entry(expiresAt = expiresAt, revoked = false) }
    }

    /**
     * Remembers the sessions as revoked (not merely forgotten) for the cache's lifetime; after that the database row
     * says the same. Call it after the revoke has committed.
     */
    fun markRevoked(jtis: Collection<String>) {
        val now = OffsetDateTime.now()
        jtis.forEach { cache.put(it, Entry(expiresAt = now, revoked = true)) }
    }

    fun isCurrentlyValid(entry: Entry, now: OffsetDateTime = OffsetDateTime.now()): Boolean =
        !entry.revoked && entry.expiresAt.isAfter(now)
}
