package com.shifa.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Pure rules for secrets at startup, so they can be unit-tested without Spring.
 * A failure is returned (not thrown) so the caller decides whether to abort.
 */
object StartupSecretPolicy {
    /** Profiles where the deployment is real and must not run with placeholder secrets. */
    private val deployedProfiles = setOf("prod", "dev", "qa")

    const val MIN_JWT_SECRET_LENGTH = 64

    data class Result(val fatal: List<String>, val warnings: List<String>)

    fun evaluate(
        activeProfiles: Collection<String>,
        clickEnabled: Boolean,
        clickSecretKey: String,
        jwtSecret: String,
    ): Result {
        if (activeProfiles.none { it in deployedProfiles }) return Result(emptyList(), emptyList())
        val fatal = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (clickEnabled && clickSecretKey.isBlank()) {
            // With a blank secret every Click callback signature would verify, so payments could be forged.
            fatal += "click.enabled=true but CLICK_SECRET_KEY is empty: set the secret or CLICK_ENABLED=false"
        }
        if (jwtSecret.length < MIN_JWT_SECRET_LENGTH) {
            // Warn only: rotating JWT_SECRET logs out every user (patients hold 365-day tokens).
            warnings += "JWT_SECRET is ${jwtSecret.length} characters; $MIN_JWT_SECRET_LENGTH or more is recommended"
        }
        return Result(fatal, warnings)
    }
}

@Component
class StartupSecretChecks(
    private val environment: Environment,
    private val click: ClickProperties,
    @Value("\${app.jwt.secret:}") private val jwtSecret: String,
) {
    private val log = LoggerFactory.getLogger(StartupSecretChecks::class.java)

    @PostConstruct
    fun verify() {
        val result = StartupSecretPolicy.evaluate(
            activeProfiles = environment.activeProfiles.toList(),
            clickEnabled = click.enabled,
            clickSecretKey = click.secretKey,
            jwtSecret = jwtSecret,
        )
        result.warnings.forEach { log.warn("Startup secret check: {}", it) }
        if (result.fatal.isNotEmpty()) {
            throw IllegalStateException("Refusing to start: " + result.fatal.joinToString("; "))
        }
    }
}
