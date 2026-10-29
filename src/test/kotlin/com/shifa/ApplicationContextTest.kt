package com.shifa

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.File
import java.sql.DriverManager

/**
 * Boots the whole application against a real PostgreSQL (the CI service, or a local one on
 * localhost:5433 with the shifa/shifa credentials from application.yml) with Flyway applying every
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
        "app.dev-seed-admin-emails=seed.admin@shifa.dev, Seed.Admin2@shifa.dev",
    ],
)
@AutoConfigureMockMvc
class ApplicationContextTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `context loads, migrations apply and entities match the schema`() {
        // Reaching this point is the assertion: the context started with ddl-auto=validate.
    }

    /**
     * The API contract the apps rely on: every path, parameter, request and response schema. A change to it
     * must come with the snapshot update in the same PR, so accidental breaking changes are visible in review.
     * To update: `./gradlew test --tests com.shifa.ApplicationContextTest` with a database on localhost:5433,
     * then copy build/openapi.json over docs/api/openapi.json.
     */
    @Test
    fun `openapi document matches the committed contract`() {
        val body = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk).andReturn().response.contentAsString
        val mapper = ObjectMapper()
        val actual = (mapper.readTree(body) as ObjectNode).apply { remove("servers") } // host-dependent
        val expected = mapper.readTree(File("docs/api/openapi.json"))
        File("build/openapi.json").writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n")
        assertEquals(
            expected,
            actual,
            "docs/api/openapi.json is stale. Run './gradlew test --tests com.shifa.ApplicationContextTest' with a database, " +
                "copy build/openapi.json over docs/api/openapi.json (CI uploads it as the openapi artifact) and commit.",
        )
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
