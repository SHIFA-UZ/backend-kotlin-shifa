package com.shifa.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * Signs and verifies JWT tokens. Includes jti (JWT ID) for session binding so
 * force-logout can revoke tokens by revoking the corresponding UserSession.
 */
@Component
class JwtService(
    @Value("\${app.jwt.secret}") private val secret: String,
    @Value("\${app.jwt.issuer}") private val issuer: String,
    @Value("\${app.jwt.accessTokenMinutesDoctor}") private val accessTokenMinutesDoctor: Long,
    @Value("\${app.jwt.accessTokenMinutesPatient}") private val accessTokenMinutesPatient: Long
) {
    private val key = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))

    data class TokenResult(val token: String, val jti: String, val expiresAt: Instant)

    fun generate(userId: Long, principal: String, role: String): TokenResult {
        val minutes = if (role == "PATIENT") accessTokenMinutesPatient else accessTokenMinutesDoctor
        val now = Instant.now()
        val exp = now.plusSeconds(minutes * 60)
        val jti = UUID.randomUUID().toString()
        val token = Jwts.builder()
            .id(jti)
            .subject(userId.toString())
            .issuer(issuer)
            .claim("principal", principal)
            .claim("role", role)
            .issuedAt(Date.from(now))
            .expiration(Date.from(exp))
            .signWith(key)
            .compact()
        return TokenResult(token, jti, exp)
    }

    fun parse(token: String) =
        Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
}
