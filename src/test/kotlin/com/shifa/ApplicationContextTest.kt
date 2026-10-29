package com.shifa

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.springframework.boot.test.context.SpringBootTest
import java.sql.DriverManager

/**
 * Boots the whole application against a real PostgreSQL (the CI service, or a local one on
 * localhost:${DB_PORT:-5433} with the default shifa/shifa credentials) with Flyway applying every
 * migration and Hibernate validating the entities against the resulting schema.
 *
 * Catches the two outages this codebase is most exposed to: a migration that does not apply, and an
 * entity that no longer matches the schema. Skipped when no database is reachable, except in CI.
 */
@SpringBootTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "logging.file.name=build/shifa-backend-test.log",
        "app.dev-seed=true", // the seed must apply to a fresh schema
    ],
)
class ApplicationContextTest {

    @Test
    fun `context loads, migrations apply and entities match the schema`() {
        // Reaching this point is the assertion: the context started with ddl-auto=validate.
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun requirePostgres() {
            val port = System.getenv("DB_PORT") ?: "5433"
            val user = System.getenv("DB_USERNAME") ?: "shifa"
            val password = System.getenv("DB_PASSWORD") ?: "shifa"
            DriverManager.setLoginTimeout(3)
            val reason = try {
                DriverManager.getConnection("jdbc:postgresql://localhost:$port/shifa", user, password).use { it.createStatement().execute("SELECT 1") }
                null
            } catch (e: Exception) {
                "PostgreSQL not reachable on localhost:$port as $user: ${e.javaClass.simpleName}"
            }
            if (reason != null && System.getenv("CI") != null) fail("$reason (the CI workflow must provide the database)")
            assumeTrue(reason == null, reason)
        }
    }
}
