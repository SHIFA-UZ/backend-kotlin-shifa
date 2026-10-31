package com.shifa

import tools.jackson.core.util.DefaultIndenter
import tools.jackson.core.util.DefaultPrettyPrinter
import tools.jackson.core.util.Separators
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.JsonNodeFeature
import tools.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
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

    @Autowired lateinit var objectMapper: tools.jackson.databind.ObjectMapper
    @Autowired lateinit var metricsService: com.shifa.admin.application.MetricsService
    @Autowired lateinit var bookingWorkflows: com.shifa.appointments.application.IntelligenceBookingWorkflowService

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
        // Same layout as the committed file (sorted keys, "key": value, one element per line) so the diff shows only changes.
        val separators = Separators.createDefaultInstance().withObjectNameValueSpacing(Separators.Spacing.AFTER)
            .withObjectEmptySeparator("").withArrayEmptySeparator("")
        val layout = DefaultPrettyPrinter(separators)
            .withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE)
        val writer = mapper.writer().with(JsonNodeFeature.WRITE_PROPERTIES_SORTED).with(layout)
        File("build/openapi.json").writeText(writer.writeValueAsString(actual) + "\n")
        assertEquals(
            expected,
            actual,
            "docs/api/openapi.json is stale. Run './gradlew test --tests com.shifa.ApplicationContextTest' with a database, " +
                "copy build/openapi.json over docs/api/openapi.json (CI uploads it as the openapi artifact) and commit.",
        )
    }

    /**
     * Session handling the apps rely on: every rejected or missing token on a protected route is a 401 with the API
     * body (the apps sign out on 401), while a stale token on a public route does not break the public call.
     */
    @Test
    fun `unauthenticated requests get 401 with the API body, public routes ignore a stale token`() {
        val stale = "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI3IiwianRpIjoieCJ9.invalidsignature"

        mockMvc.perform(get("/api/patients/me/profile").header("Authorization", stale))
            .andExpect(status().isUnauthorized)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("TOKEN_INVALID"))

        mockMvc.perform(get("/api/patients/me/profile"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))

        mockMvc.perform(get("/api/public/doctors").header("Authorization", stale))
            .andExpect(status().isOk)
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

    /**
     * Jackson 3 (Boot 4) changed defaults that would have turned requests the apps send today into 400s or reordered
     * response keys; application.yml and JacksonConfig keep the Jackson 2 behaviour. A null for a non-null Boolean
     * becomes false, a null inside a list is accepted, trailing content is ignored and keys keep declaration order.
     */
    @Test
    fun `jackson keeps the request leniency and key order of the Jackson 2 contract`() {
        val parsed = objectMapper.readValue(
            """{"id":null,"label":"Clinic","isPrimary":null,"tags":["a",null]} trailing""",
            LenientDto::class.java,
        )
        assertEquals(LenientDto(null, "Clinic", false, listOf("a", null as String?) as List<String>), parsed)
        assertTrue(objectMapper.writeValueAsString(LenientDto(1, "x")).startsWith("""{"id":1,"label":"x","isPrimary":false"""))
    }

    data class LenientDto(val id: Long?, val label: String, val isPrimary: Boolean = false, val tags: List<String> = emptyList())

    /** The admin metrics read runs every query against the real schema; the route is ADMIN only; the page is public. */
    @Test
    fun `admin metrics snapshot reads the database, and only admins may ask for it`() {
        val m = metricsService.snapshot()
        assertTrue((m.database.schemaVersion ?: 0) >= 121, "schema version ${m.database.schemaVersion}")
        assertTrue(m.database.tables.isNotEmpty())
        assertTrue(m.database.sizeBytes > 0)
        assertTrue(m.jvm.heapMaxMb > 0)
        assertTrue(m.app.uptimeSeconds >= 0)

        mockMvc.perform(get("/api/admin/metrics")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/admin-metrics/index.html")).andExpect(status().isOk)
        mockMvc.perform(get("/admin-metrics/app.js")).andExpect(status().isOk)
        mockMvc.perform(get("/api/admin/storage/migrate")).andExpect(status().isUnauthorized)
    }

    /**
     * The workflow lookup expires stale rows (an UPDATE) before it reads. Marked read-only in Phase 6, it failed
     * on PostgreSQL with "cannot execute UPDATE in a read-only transaction" and took Shifa Intelligence down.
     */
    @Test
    fun `booking workflow lookups run in a read-write transaction`() {
        assertNull(bookingWorkflows.active(doctorId = 1L, conversationId = "context-test-conversation"))
        assertNull(bookingWorkflows.activeReschedule(doctorId = 1L, conversationId = "context-test-conversation"))
    }
}
