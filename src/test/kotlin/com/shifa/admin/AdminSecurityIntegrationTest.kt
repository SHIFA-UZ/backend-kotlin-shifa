package com.shifa.admin

import com.shifa.ApplicationContextTest
import com.shifa.admin.domain.AdminLevel
import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import com.shifa.auth.domain.UserSession
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * Who may do what under `/api/admin`, and the sign-out, through the real filter chain against PostgreSQL. Same context
 * as [ApplicationContextTest] (cached). The admins are made here with their own e-mail suffix and removed afterwards.
 */
@SpringBootTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "logging.file.name=build/shifa-backend-test.log",
        "app.dev-seed=true",
        "app.dev-seed-admin-emails=seed.admin@shifa.dev, Seed.Admin2@shifa.dev",
    ],
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminSecurityIntegrationTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var jwt: com.shifa.shared.security.JwtService
    @Autowired lateinit var userSessions: com.shifa.auth.application.UserSessions
    @Autowired lateinit var userManagement: com.shifa.auth.application.UserManagementService
    @Autowired lateinit var users: com.shifa.auth.application.Users
    @Autowired lateinit var sessionCache: com.shifa.shared.security.UserSessionValidationCache
    @Autowired lateinit var userDeletion: com.shifa.auth.application.UserDeletionService
    @Autowired lateinit var tx: org.springframework.transaction.PlatformTransactionManager
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    lateinit var handlerMapping: RequestMappingHandlerMapping

    private val suffix = "@admin-security-${UUID.randomUUID().toString().take(8)}.test"
    private val password = "Str0ng!Passw0rd"

    companion object {
        @JvmStatic
        @BeforeAll
        fun requirePostgres() = ApplicationContextTest.requirePostgres()
    }

    @AfterAll
    fun removeTestAdmins() {
        val mine = "SELECT id FROM users WHERE email LIKE '%$suffix'"
        jdbc.update("DELETE FROM audit_logs WHERE admin_user_id IN ($mine)")
        listOf("user_sessions", "user_roles", "admin_profiles").forEach { jdbc.update("DELETE FROM $it WHERE user_id IN ($mine)") }
        jdbc.update("DELETE FROM users WHERE email LIKE '%$suffix'")
    }

    private fun admin(level: AdminLevel): User =
        userManagement.createAdminUser("${level.name.lowercase()}-${UUID.randomUUID()}$suffix", password, "Test", "Admin", level)

    /** A signed-in session, as the sign-in routes make it. */
    private fun token(user: User, role: Role = Role.ADMIN): String {
        val result = jwt.generate(user.id, user.email ?: user.id.toString(), role.name)
        userSessions.save(
            UserSession(user = user, tokenJti = result.jti, expiresAt = OffsetDateTime.ofInstant(result.expiresAt, ZoneOffset.UTC)),
        )
        return result.token
    }

    private fun call(method: HttpMethod, path: String, token: String?, body: String = "{}"): ResultActions =
        mockMvc.perform(
            request(method, path).contentType(MediaType.APPLICATION_JSON).content(body)
                // its own rate-limit bucket: the auth routes allow ten calls a minute per address
                .header("X-Forwarded-For", "10.99.${(0..250).random()}.${(1..250).random()}")
                .apply { token?.let { header("Authorization", "Bearer $it") } },
        )

    private fun post(path: String, token: String?, body: String = "{}") = call(HttpMethod.POST, path, token, body)

    // ---------- read-only admins ----------

    /** Bodies that pass `@Valid` and parsing, so the handler itself is what answers. */
    private val validBodies = mapOf(
        "/api/admin/users/create-admin" to
            """{"email":"never-made$suffix","password":"$password","firstName":"A","lastName":"B"}""",
        "/api/admin/users/{userId}/subscription-tier" to """{"tier":"PRO"}""",
        "/api/admin/users/{userId}/enable" to """{"enabled":true}""",
        "/api/admin/clinics" to """{"name":"Never made"}""",
        "/api/admin/clinics/{clinicId}" to """{"name":"Never made"}""",
        "/api/admin/clinics/{clinicId}/doctors/{doctorProfileId}/role" to """{"membershipRole":"OWNER"}""",
        "/api/admin/doctors/{doctorId}/sms-reminders-allowed" to """{"allowed":true}""",
        "/api/admin/config/{key}" to """{"value":"x"}""",
    )

    /** Reads sent as POST (a search with a body); they change nothing. */
    private val readsByPost = setOf("/api/admin/patients/deleted/search")

    @Test
    fun `a read-only admin is refused by every writing route under api admin`() {
        val readOnly = token(admin(AdminLevel.READ_ONLY))
        val contractsBefore = jdbc.queryForObject("SELECT count(*) FROM early_partner_contract", Long::class.java)

        val writes = handlerMapping.handlerMethods.keys.flatMap { info ->
            val methods = info.methodsCondition.methods.map { it.asHttpMethod() }
            info.patternValues.flatMap { pattern -> methods.map { it to pattern } }
        }.filter { (method, pattern) -> pattern.startsWith("/api/admin/") && method != HttpMethod.GET && pattern !in readsByPost }

        // The two this test was written for must be on the list, or the sweep proves nothing.
        assertTrue(HttpMethod.POST to "/api/admin/doctors/{doctorId}/early-partner-contract/issue" in writes)
        assertTrue(HttpMethod.POST to "/api/admin/payments/webhooks/stripe/failed/{paymentEventId}/retry" in writes)

        val notRefused = writes.mapNotNull { (method, pattern) ->
            val path = pattern.replace(Regex("\\{[^}]+}"), "1")
            val answer = call(method, path, readOnly, validBodies[pattern] ?: "{}").andReturn().response.status
            "$method $pattern -> $answer".takeIf { answer != 403 }
        }
        assertEquals(emptyList<String>(), notRefused, "writes a READ_ONLY admin was not refused")
        assertEquals(contractsBefore, jdbc.queryForObject("SELECT count(*) FROM early_partner_contract", Long::class.java))
    }

    // ---------- admin levels ----------

    @Test
    fun `only a super admin makes a super admin`() {
        val adminToken = token(admin(AdminLevel.ADMIN))
        val superToken = token(admin(AdminLevel.SUPER_ADMIN))
        fun body(level: String) =
            """{"email":"made-${UUID.randomUUID()}$suffix","password":"$password","firstName":"A","lastName":"B","adminLevel":"$level"}"""

        post("/api/admin/users/create-admin", adminToken, body("SUPER_ADMIN")).andExpect(status().isForbidden)
        post("/api/admin/users/create-admin", adminToken, body("ADMIN")).andExpect(status().isOk)
        post("/api/admin/users/create-admin", adminToken, body("READ_ONLY")).andExpect(status().isOk)
        post("/api/admin/users/create-admin", superToken, body("SUPER_ADMIN")).andExpect(status().isOk)
    }

    @Test
    fun `an admin cannot act on another admin's account, a super admin can, and everyone can on their own`() {
        val admin = admin(AdminLevel.ADMIN)
        val adminToken = token(admin)
        val superAdmin = admin(AdminLevel.SUPER_ADMIN)
        val otherAdmin = admin(AdminLevel.ADMIN)
        val readOnly = admin(AdminLevel.READ_ONLY)

        listOf(superAdmin, otherAdmin, readOnly).forEach { target ->
            val base = "/api/admin/users/${target.id}"
            post("$base/reset-password", adminToken).andExpect(status().isForbidden)
            post("$base/enable", adminToken, """{"enabled":false}""").andExpect(status().isForbidden)
            post("$base/force-logout", adminToken).andExpect(status().isForbidden)
            post("$base/unlock", adminToken).andExpect(status().isForbidden)
            call(HttpMethod.DELETE, base, adminToken).andExpect(status().isForbidden)
            call(HttpMethod.PATCH, "$base/subscription-tier", adminToken, """{"tier":"PRO"}""").andExpect(status().isForbidden)
            assertTrue(users.findById(target.id).get().enabled)
        }

        // A doctor's account stays an ordinary admin's work.
        val doctorId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'seed.doctor1@shifa.dev'", Long::class.java)
        post("/api/admin/users/$doctorId/unlock", adminToken).andExpect(status().isOk)

        val superToken = token(superAdmin)
        post("/api/admin/users/${otherAdmin.id}/unlock", superToken).andExpect(status().isOk)
        post("/api/admin/users/${otherAdmin.id}/enable", superToken, """{"enabled":false}""").andExpect(status().isOk)
        post("/api/admin/users/${otherAdmin.id}/reset-password", superToken).andExpect(status().isOk)
        post("/api/admin/users/${otherAdmin.id}/force-logout", superToken).andExpect(status().isOk)

        // Own account: the panel signs out this way.
        post("/api/admin/users/${admin.id}/force-logout", adminToken).andExpect(status().isOk)
        get("/api/admin/users/stats").let { mockMvc.perform(it.header("Authorization", "Bearer $adminToken")) }
            .andExpect(status().isUnauthorized)
    }

    /**
     * Runs with every other super admin of the database switched off inside a transaction that is rolled back, so the
     * one made here is the last. (The requests run on this thread and join the transaction.)
     */
    @Test
    fun `the last enabled super admin cannot be disabled or deleted, by anyone`() {
        val last = admin(AdminLevel.SUPER_ADMIN)
        val lastToken = token(last)
        val second = admin(AdminLevel.SUPER_ADMIN)
        val secondToken = token(second)
        val disable = """{"enabled":false}"""

        org.springframework.transaction.support.TransactionTemplate(tx).execute { status ->
            status.setRollbackOnly()
            jdbc.update(
                "UPDATE users SET enabled = false WHERE id NOT IN (?, ?) AND id IN " +
                    "(SELECT user_id FROM admin_profiles WHERE admin_level = 'SUPER_ADMIN')",
                last.id, second.id,
            )
            // Two are left: one may go, by its own hand too.
            userManagement.setUserEnabled(second.id, false)
            // One is left: not by itself, not by a service call, not by deletion.
            org.junit.jupiter.api.assertThrows<com.shifa.shared.CodedStatusException> { userManagement.setUserEnabled(last.id, false) }
            org.junit.jupiter.api.assertThrows<com.shifa.shared.CodedStatusException> { userDeletion.deleteUser(last.id) }
            assertTrue(users.findById(last.id).get().enabled)
            userManagement.setUserEnabled(last.id, true) // enabling is never refused
        }

        // Through the route, with both enabled again: the answer's shape. Everyone else is switched off for the call only.
        post("/api/admin/users/${second.id}/enable", lastToken, disable).andExpect(status().isOk)
        val others = jdbc.queryForList(
            "SELECT user_id FROM admin_profiles a JOIN users u ON u.id = a.user_id " +
                "WHERE a.admin_level = 'SUPER_ADMIN' AND u.enabled AND u.id <> ?",
            Long::class.java, last.id,
        )
        try {
            others.forEach { jdbc.update("UPDATE users SET enabled = false WHERE id = ?", it) }
            post("/api/admin/users/${last.id}/enable", lastToken, disable)
                .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("LAST_SUPER_ADMIN"))
            assertTrue(users.findById(last.id).get().enabled)
        } finally {
            others.forEach { jdbc.update("UPDATE users SET enabled = true WHERE id = ?", it) }
        }
        post("/api/admin/users/${last.id}/enable", secondToken, disable).andExpect(status().isForbidden) // second is disabled
    }

    // ---------- create-admin input ----------

    @Test
    fun `create-admin checks its fields, the password policy and a taken e-mail in any letter case`() {
        val superToken = token(admin(AdminLevel.SUPER_ADMIN))
        val email = "Taken-${UUID.randomUUID()}$suffix"
        fun body(email: String, password: String = this.password, firstName: String = "A") =
            """{"email":"$email","password":"$password","firstName":"$firstName","lastName":"B"}"""

        post("/api/admin/users/create-admin", superToken, body(email, firstName = " "))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION"))
        post("/api/admin/users/create-admin", superToken, body("not-an-address"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION"))
        post("/api/admin/users/create-admin", superToken, body(email, password = "weakpassword"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("Password must contain at least one uppercase letter"))

        post("/api/admin/users/create-admin", superToken, body(email)).andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value(email.lowercase()))
        listOf(email, email.lowercase(), email.uppercase()).forEach {
            post("/api/admin/users/create-admin", superToken, body(it))
                .andExpect(status().isConflict).andExpect(jsonPath("$.message").value("Email already registered"))
        }
    }

    // ---------- sign-out ----------

    @Test
    fun `logout ends the caller's own session and no other, for every role and admin level`() {
        fun statsWith(token: String) = mockMvc.perform(get("/api/admin/users/stats").header("Authorization", "Bearer $token"))

        val admin = admin(AdminLevel.READ_ONLY)
        val here = token(admin)
        val otherBrowser = token(admin)
        statsWith(here).andExpect(status().isOk) // the session is in the validity cache now, as in real use

        post("/api/auth/logout", here).andExpect(status().isNoContent)

        statsWith(here).andExpect(status().isUnauthorized)
        // A request that read the session just before the sign-out writes it to the cache late: it stays signed out.
        sessionCache.putValid(jwt.parse(here).id, OffsetDateTime.now().plusHours(1))
        statsWith(here).andExpect(status().isUnauthorized)
        statsWith(otherBrowser).andExpect(status().isOk)
        post("/api/auth/logout", here).andExpect(status().isUnauthorized) // already signed out
        post("/api/auth/logout", null).andExpect(status().isUnauthorized)

        val doctor = users.findByEmailIgnoreCase("seed.doctor1@shifa.dev").get()
        val doctorToken = token(doctor, Role.DOCTOR)
        val before = jdbc.queryForObject("SELECT count(*) FROM user_sessions WHERE user_id = ? AND NOT revoked", Long::class.java, doctor.id)
        post("/api/auth/logout", doctorToken).andExpect(status().isNoContent)
        assertEquals(
            before!! - 1,
            jdbc.queryForObject("SELECT count(*) FROM user_sessions WHERE user_id = ? AND NOT revoked", Long::class.java, doctor.id),
        )
    }
}
