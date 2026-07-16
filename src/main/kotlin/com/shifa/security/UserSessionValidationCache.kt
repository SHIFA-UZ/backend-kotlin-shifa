package com.shifa.security

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Short-lived in-memory cache of JWT session validity by `jti`.
 * Cuts repeated DB hits on [JwtAuthFilter] for the same token.
 *
 * Force-logout must call [invalidate] / [invalidateAll] so revoke is visible within this JVM.
 * Multi-replica deploys need a shared store (e.g. Redis) for instant cross-instance revoke.
 */
@Component
class UserSessionValidationCache {

    data class Entry(
        val expiresAt: OffsetDateTime,
        val revoked: Boolean,
    )

    private val cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(2))
        .maximumSize(50_000)
        .build<String, Entry>()

    fun get(jti: String): Entry? = cache.getIfPresent(jti)

    fun putValid(jti: String, expiresAt: OffsetDateTime) {
        cache.put(jti, Entry(expiresAt = expiresAt, revoked = false))
    }

    fun invalidate(jti: String) {
        cache.invalidate(jti)
    }

    fun invalidateAll(jtis: Collection<String>) {
        if (jtis.isEmpty()) return
        cache.invalidateAll(jtis)
    }

    fun isCurrentlyValid(entry: Entry, now: OffsetDateTime = OffsetDateTime.now()): Boolean =
        !entry.revoked && entry.expiresAt.isAfter(now)
}
