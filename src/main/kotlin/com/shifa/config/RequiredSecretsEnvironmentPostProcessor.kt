package com.shifa.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment

/**
 * Fails startup with the names of the missing environment variables before any bean is created.
 *
 * Spring's binder keeps an unresolvable placeholder such as `${SPRING_DATASOURCE_PASSWORD}` as a literal, so a
 * missing secret would otherwise surface as a generic database or provider error. Resolving each secret property
 * here throws "Could not resolve placeholder 'NAME'"; the names are collected into one message.
 * Properties that a profile sets to a literal (e.g. "" on development) resolve fine and are not reported.
 * Registered in META-INF/spring.factories; runs after the yml files are loaded.
 */
class RequiredSecretsEnvironmentPostProcessor : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val missing = RequiredSecrets.missing(environment)
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Refusing to start: missing environment variables ${missing.joinToString(", ")} " +
                    "(set them in Railway for this environment, or in application-local.yml locally)",
            )
        }
    }
}

object RequiredSecrets {
    /** Every property whose deployed value is a secret placeholder (application-prod.yml). */
    val properties = listOf(
        "spring.datasource.url", "spring.datasource.username", "spring.datasource.password",
        "app.jwt.secret", "spring.mail.password",
        "openai.apiKey", "ai.providers.openai.api-key",
        "daily.apiKey", "scribe.daily-webhook-secret",
        "stripe.secretKey", "stripe.webhookSecret",
        "click.secret-key", "devsms.api-token", "googleMaps.apiKey",
        "app.storage.s3.bucket", "app.storage.s3.access-key", "app.storage.s3.secret-key",
    )

    private val placeholderName = Regex("""placeholder '([^']+)'""")

    /** Names of the unresolvable environment variables, in property order, without duplicates. */
    fun missing(environment: ConfigurableEnvironment): List<String> =
        properties.mapNotNull { key ->
            try {
                environment.getProperty(key)
                null
            } catch (e: IllegalArgumentException) {
                placeholderName.find(e.message.orEmpty())?.groupValues?.get(1) ?: key
            }
        }.distinct()
}
