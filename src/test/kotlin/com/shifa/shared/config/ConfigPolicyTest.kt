package com.shifa.shared.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource

/**
 * The configuration policy from the roadmap (3.6): only secrets and Railway-injected values are environment
 * variables, nothing else has a placeholder, and the deployed profiles have no defaults for secrets.
 * The golden values pin what production and development run with, so a consolidation cannot silently change them.
 */
class ConfigPolicyTest {

    private val files = listOf("application.yml", "application-prod.yml", "application-dev.yml")

    private val allowedVariables = setOf(
        // Railway-injected
        "PORT", "RAILWAY_GIT_COMMIT_SHA",
        "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD",
        // secrets
        "JWT_SECRET", "OPENAI_API_KEY", "GEMINI_API_KEY", "XAI_API_KEY", "DAILY_API_KEY", "DAILY_WEBHOOK_SECRET",
        "STRIPE_SECRET_KEY", "STRIPE_WEBHOOK_SECRET", "CLICK_SECRET_KEY", "DEVSMS_API_TOKEN", "MAIL_PASSWORD",
        "GOOGLE_MAPS_API_KEY",
        // the environment's own bucket (Railway reference variables)
        "S3_BUCKET", "S3_ACCESS_KEY", "S3_SECRET_KEY",
        // JVM property, not an environment variable
        "java.io.tmpdir",
    )

    private val placeholder = Regex("""\$\{([A-Za-z0-9_.]+)(:[^}]*)?}""")

    @Test
    fun `only secrets and Railway-injected values are placeholders`() {
        val found = files.flatMap { file ->
            placeholder.findAll(ClassPathResource(file).inputStream.bufferedReader().readText())
                .map { file to it.groupValues[1] }
        }
        val unexpected = found.filter { it.second !in allowedVariables }
        assertTrue(unexpected.isEmpty(), "Non-secret placeholders (hardcode them in yml instead): $unexpected")
    }

    @Test
    fun `deployed profiles give secrets no default`() {
        for (file in listOf("application-prod.yml", "application-dev.yml")) {
            val text = ClassPathResource(file).inputStream.bufferedReader().readText()
            val withDefault = placeholder.findAll(text).filter { it.groupValues[2].isNotEmpty() }.map { it.value }.toList()
            assertTrue(withDefault.isEmpty(), "$file placeholders with a default: $withDefault")
        }
    }

    @Test
    fun `production golden values`() {
        val env = environment("prod")
        assertEquals("https://shifa-doc-backend-mvp-production.up.railway.app", env.getProperty("app.publicBaseUrl"))
        assertEquals("https://doctor.shifa.uz", env.getProperty("app.frontendUrl"))
        assertEquals("/app/storage/images", env.getProperty("app.storageRoot"))
        assertEquals("CLICK,STRIPE", env.getProperty("payment.enabled-providers"))
        assertEquals("true", env.getProperty("click.enabled"))
        assertEquals("64374", env.getProperty("click.merchant-id"))
        assertEquals("111453", env.getProperty("click.service-id"))
        assertEquals("91085", env.getProperty("click.merchant-user-id"))
        assertEquals("https://shifa-doctor-staging.web.app/home", env.getProperty("stripe.connectRefreshUrl"))
        assertEquals("https://shifa-doctor-staging.web.app/profile", env.getProperty("stripe.connectReturnUrl"))
        assertEquals("a77ba9001@smtp-brevo.com", env.getProperty("spring.mail.username"))
        assertEquals("noreply@shifa.uz", env.getProperty("spring.mail.from"))
        assertEquals("SHIFA", env.getProperty("spring.mail.from-name"))
        assertEquals("SHIFA.UZ", env.getProperty("devsms.sender-from"))
        assertEquals("shifa", env.getProperty("app.jwt.issuer"))
        assertEquals("validate", env.getProperty("spring.jpa.hibernate.ddl-auto"))
        assertEquals("false", env.getProperty("app.dev-seed"))
        assertEquals("false", env.getProperty("springdoc.api-docs.enabled"))
        assertEquals("false", env.getProperty("app.storage.s3.enabled"))
        assertEquals("secret-from-railway", env.getProperty("app.jwt.secret"))
        assertEquals("jdbc:postgresql://db:5432/railway", env.getProperty("spring.datasource.url"))
    }

    @Test
    fun `development golden values`() {
        val env = environment("prod", "dev", secrets = railwaySecrets - developmentDoesNotSet)
        assertEquals("https://api-dev.shifa.uz", env.getProperty("app.publicBaseUrl"))
        assertEquals("https://doctor.shifa.uz", env.getProperty("app.frontendUrl"))
        assertEquals("NONE", env.getProperty("payment.enabled-providers"))
        assertEquals("false", env.getProperty("click.enabled"))
        assertEquals("", env.getProperty("stripe.secretKey"))
        assertEquals("", env.getProperty("stripe.webhookSecret"))
        assertEquals("", env.getProperty("devsms.api-token"))
        assertEquals("true", env.getProperty("app.dev-seed"))
        assertEquals("true", env.getProperty("springdoc.api-docs.enabled"))
        assertEquals("validate", env.getProperty("spring.jpa.hibernate.ddl-auto"))
        assertEquals("true", env.getProperty("app.storage.s3.enabled"))
        assertEquals("shifa-uploads-test", env.getProperty("app.storage.s3.bucket"))
    }

    @Test
    fun `a missing secret fails resolution by name`() {
        val env = environment("prod", secrets = emptyMap())
        val error = assertThrows(IllegalArgumentException::class.java) { env.getProperty("app.jwt.secret") }
        assertTrue(error.message!!.contains("JWT_SECRET"), error.message)
    }

    @Test
    fun `startup check names every missing variable, including the datasource ones the binder would not`() {
        val missing = RequiredSecrets.missing(environment("prod", secrets = emptyMap()))
        assertEquals(
            listOf(
                "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD", "JWT_SECRET",
                "MAIL_PASSWORD", "OPENAI_API_KEY", "DAILY_API_KEY", "DAILY_WEBHOOK_SECRET", "STRIPE_SECRET_KEY",
                "STRIPE_WEBHOOK_SECRET", "CLICK_SECRET_KEY", "DEVSMS_API_TOKEN", "GOOGLE_MAPS_API_KEY",
            ),
            missing,
        )
        val error = assertThrows(IllegalStateException::class.java) {
            RequiredSecretsEnvironmentPostProcessor().postProcessEnvironment(environment("prod", secrets = emptyMap()), SpringApplication())
        }
        assertTrue(error.message!!.contains("SPRING_DATASOURCE_PASSWORD"), error.message)
    }

    @Test
    fun `startup check passes with each environment's variables, and locally without any`() {
        assertEquals(emptyList<String>(), RequiredSecrets.missing(environment("prod")))
        assertEquals(emptyList<String>(), RequiredSecrets.missing(environment("prod", "dev", secrets = railwaySecrets - developmentDoesNotSet)))
        assertEquals(emptyList<String>(), RequiredSecrets.missing(environment(secrets = emptyMap())))
    }

    /** The yml files as Spring layers them: later profiles win, then prod, then the base; secrets on top. */
    private fun environment(vararg profiles: String, secrets: Map<String, Any> = railwaySecrets): StandardEnvironment {
        val env = StandardEnvironment()
        env.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        env.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
        val loader = YamlPropertySourceLoader()
        (listOf("application.yml") + profiles.map { "application-$it.yml" }).forEach { file ->
            loader.load(file, ClassPathResource(file)).forEach { env.propertySources.addFirst(it) }
        }
        env.propertySources.addFirst(MapPropertySource("railway", secrets))
        return env
    }

    private val developmentDoesNotSet = setOf("STRIPE_SECRET_KEY", "STRIPE_WEBHOOK_SECRET", "DEVSMS_API_TOKEN")

    private val railwaySecrets: Map<String, Any> = mapOf(
        "SPRING_DATASOURCE_URL" to "jdbc:postgresql://db:5432/railway",
        "SPRING_DATASOURCE_USERNAME" to "postgres",
        "SPRING_DATASOURCE_PASSWORD" to "pw",
        "JWT_SECRET" to "secret-from-railway",
        "OPENAI_API_KEY" to "k", "DAILY_API_KEY" to "k", "DAILY_WEBHOOK_SECRET" to "k", "MAIL_PASSWORD" to "k",
        "STRIPE_SECRET_KEY" to "k", "STRIPE_WEBHOOK_SECRET" to "k", "CLICK_SECRET_KEY" to "k",
        "DEVSMS_API_TOKEN" to "k", "GOOGLE_MAPS_API_KEY" to "k",
        "S3_BUCKET" to "shifa-uploads-test", "S3_ACCESS_KEY" to "k", "S3_SECRET_KEY" to "k",
    )
}
