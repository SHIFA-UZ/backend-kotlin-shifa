package com.shifa.admin

import com.shifa.ApplicationContextTest
import com.shifa.admin.api.AdminController
import com.shifa.admin.api.AdminDeletedPatientsController
import com.shifa.admin.api.AdminDoctorActivityController
import com.shifa.admin.api.dto.DeletedPatientSearchRequest
import com.shifa.admin.api.dto.DoctorSubscriptionBillingRequest
import com.shifa.admin.api.dto.SmsRemindersAllowedRequest
import com.shifa.admin.application.AuditService
import com.shifa.admin.domain.AdminProfile
import com.shifa.auth.application.UserActivityService
import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import com.shifa.shared.security.AdminPrincipal
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.convert.ConversionService
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The admin audit trail against PostgreSQL with the dev seed: which admin writes leave a row, what the row holds
 * (reason, old and new value, IP and user agent) and how the audit and activity lists filter, window and sort.
 * Same context as [ApplicationContextTest] (cached). The writes under test run in a rolled-back transaction; the
 * audit rows commit on their own (REQUIRES_NEW) and are removed by their marker after each test.
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
class AdminAuditTrailIntegrationTest {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var tx: org.springframework.transaction.PlatformTransactionManager
    @Autowired lateinit var objectMapper: tools.jackson.databind.ObjectMapper
    @Autowired lateinit var principals: com.shifa.shared.security.PrincipalResolverService
    @Autowired lateinit var users: com.shifa.auth.application.Users
    @Autowired lateinit var auditService: AuditService
    @Autowired lateinit var userActivityService: UserActivityService
    @Autowired lateinit var adminController: AdminController
    @Autowired lateinit var doctorActivityController: AdminDoctorActivityController
    @Autowired lateinit var deletedPatientsController: AdminDeletedPatientsController

    @Autowired
    @Qualifier("mvcConversionService")
    lateinit var conversion: ConversionService

    companion object {
        @JvmStatic
        @BeforeAll
        fun requirePostgres() = ApplicationContextTest.requirePostgres()
    }

    /** Marks every row this test writes: the user agent of the audit rows, a suffix of the made-up types. */
    private val marker = "audit-test-" + UUID.randomUUID().toString().take(8)
    private val ip = "203.0.113.7"

    @AfterEach
    fun removeRows() {
        jdbc.update("DELETE FROM audit_logs WHERE user_agent = ?", marker)
        jdbc.update("DELETE FROM user_activity_logs WHERE user_agent = ?", marker)
    }

    private fun request() = MockHttpServletRequest().apply {
        remoteAddr = ip
        addHeader("User-Agent", marker)
    }

    private fun admin(): AdminPrincipal {
        val userId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'seed.admin@shifa.dev'", Long::class.java)!!
        return principals.getPrincipalForToken(userId, "ADMIN") as AdminPrincipal
    }

    private fun doctorId(): Long = jdbc.queryForObject(
        "SELECT d.id FROM doctor_profiles d JOIN users u ON u.id = d.user_id WHERE u.email = 'seed.doctor1@shifa.dev'",
        Long::class.java,
    )!!

    private fun <T> rolledBack(block: () -> T): T =
        TransactionTemplate(tx).execute { status -> block().also { status.setRollbackOnly() } }!!

    private class Row(val entityId: Long?, val details: Map<*, *>, val ip: String?, val userAgent: String?)

    /** The newest audit row of this test with the action; fails when there is none. */
    private fun row(actionType: String): Row = jdbc.query(
        "SELECT entity_id, details::text AS details, ip_address, user_agent FROM audit_logs " +
            "WHERE user_agent = ? AND action_type = ? ORDER BY id DESC LIMIT 1",
        { rs, _ ->
            Row(
                (rs.getObject("entity_id") as Number?)?.toLong(),
                objectMapper.readValue(rs.getString("details") ?: "{}", Map::class.java),
                rs.getString("ip_address"),
                rs.getString("user_agent"),
            )
        },
        marker, actionType,
    ).single()

    // ---------- reason on the destructive actions ----------

    @Test
    fun `reset calendar stores the trimmed reason in the audit row, and none when nothing is sent`() {
        val doctorId = doctorId()
        rolledBack { adminController.resetDoctorCalendar(doctorId, "  duplicate schedule  ", admin(), request()) }
        assertEquals("duplicate schedule", row("RESET_DOCTOR_CALENDAR").details["reason"])

        rolledBack { adminController.resetDoctorCalendar(doctorId, null, admin(), request()) } // what the panel sends today
        assertFalse(row("RESET_DOCTOR_CALENDAR").details.containsKey("reason"))
    }

    @Test
    fun `delete user stores the reason, and a reason that is too long stops the delete`() {
        val email = "$marker@example.com"
        val userId = users.save(User(email = email, passwordHash = "x", role = Role.PATIENT)).id
        try {
            val tooLong = "x".repeat(AdminController.MAX_REASON_LENGTH + 1)
            val refused = assertThrows<ResponseStatusException> { adminController.deleteUser(userId, tooLong, admin(), request()) }
            assertEquals(400, refused.statusCode.value())
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Int::class.java, userId))

            adminController.deleteUser(userId, "test account", admin(), request())
            val row = row("USER_DELETED")
            assertEquals(userId, row.entityId)
            assertEquals("test account", row.details["reason"])
            assertEquals("PATIENT", row.details["deletedRole"])
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?", userId)
        }
    }

    // ---------- admin writes that had no row ----------

    @Test
    fun `billing, SMS permission and contract issue each leave an audit row with IP and user agent`() {
        val doctorId = doctorId()
        // The row is written in the transaction of the write, so it is read (and rolled back) inside it.
        rolledBack {
            doctorActivityController.updateSubscriptionBilling(admin(), doctorId, DoctorSubscriptionBillingRequest(monthlyChargeUsd = 45), request())
            doctorActivityController.setSmsRemindersAllowed(admin(), doctorId, SmsRemindersAllowedRequest(allowed = true), request())
            doctorActivityController.issueEarlyPartnerContract(admin(), doctorId, request())

            val billing = row("DOCTOR_SUBSCRIPTION_BILLING_CHANGED")
            assertEquals(doctorId, billing.entityId)
            assertEquals(listOf("monthlyChargeUsd"), billing.details["changed"])
            assertEquals(45, billing.details["monthlyChargeUsd"])
            assertNotNull(billing.details["trialPeriodMonths"])
            assertEquals(ip, billing.ip)
            assertEquals(marker, billing.userAgent)

            assertEquals(true, row("DOCTOR_SMS_REMINDERS_ALLOWED_CHANGED").details["allowed"])

            val contract = row("EARLY_PARTNER_CONTRACT_ISSUED")
            assertEquals(doctorId, contract.entityId)
            assertTrue((contract.details["contractNumber"] as String).startsWith("SHIFA-"))
            assertEquals(setOf("contractNumber", "newAllocation"), contract.details.keys) // no partner name, phone or e-mail
        }
    }

    @Test
    fun `a write whose audit row cannot be stored is not done`() {
        val doctorId = doctorId()
        fun doctor() = jdbc.queryForMap("SELECT admin_monthly_charge_usd AS charge, sms_reminders_allowed AS sms FROM doctor_profiles WHERE id = ?", doctorId)
        val before = doctor()
        // An admin account that does not exist: the audit insert breaks its foreign key.
        val ghost = AdminPrincipal(AdminProfile(user = User(id = -99, passwordHash = "x", role = Role.ADMIN), firstName = "No", lastName = "One"), emptyList())

        assertThrows<Exception> {
            doctorActivityController.updateSubscriptionBilling(ghost, doctorId, DoctorSubscriptionBillingRequest(monthlyChargeUsd = 15), request())
        }
        assertThrows<Exception> {
            doctorActivityController.setSmsRemindersAllowed(ghost, doctorId, SmsRemindersAllowedRequest(allowed = before["sms"] != true), request())
        }
        assertThrows<Exception> { doctorActivityController.issueEarlyPartnerContract(ghost, doctorId, request()) }

        assertEquals(before, doctor())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE user_agent = ?", Int::class.java, marker))
    }

    @Test
    fun `control characters in a reason are refused before the action, and no stored value can break the audit insert`() {
        val doctorId = doctorId()
        val appointments = { jdbc.queryForObject("SELECT count(*) FROM appointments WHERE doctor_id = ?", Int::class.java, doctorId) }
        val before = appointments()
        for (bad in listOf("a\u0000b", "bell\u0007", "esc\u001b[0m")) {
            val refused = assertThrows<ResponseStatusException> { adminController.resetDoctorCalendar(doctorId, bad, admin(), request()) }
            assertEquals(400, refused.statusCode.value())
            assertEquals(400, assertThrows<ResponseStatusException> { adminController.deleteUser(-1, bad, admin(), request()) }.statusCode.value())
        }
        assertEquals(before, appointments())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE user_agent = ?", Int::class.java, marker))

        rolledBack { adminController.resetDoctorCalendar(doctorId, "line one\n\tline two", admin(), request()) }
        assertEquals("line one\n\tline two", row("RESET_DOCTOR_CALENDAR").details["reason"])

        // The writer itself: NUL and a lone surrogate, at any depth, are dropped instead of failing the insert.
        auditService.logAction(
            admin().adminProfile.user, "TEST_NUL_$marker", "TEST",
            details = mapOf("a\u0000" to "x\u0000y", "nested" to mapOf("k" to listOf("p\uD83Dq", "ok \uD83D\uDE00"))),
            request = request(),
        )
        val details = row("TEST_NUL_$marker").details
        assertEquals("xy", details["a"])
        assertEquals(mapOf("k" to listOf("pq", "ok \uD83D\uDE00")), details["nested"])
    }

    // ---------- config ----------

    @Test
    fun `a config change records the value before and the value after`() {
        val before = jdbc.queryForObject("SELECT config_value FROM system_config WHERE config_key = 'maintenance_mode'", String::class.java)
        val after = "$before-changed"
        rolledBack { adminController.updateConfig("maintenance_mode", mapOf("value" to after), admin(), request()) }

        val row = row("CONFIG_UPDATED")
        assertEquals(before, row.details["oldValue"])
        assertEquals(after, row.details["newValue"])
    }

    // ---------- deleted patients ----------

    @Test
    fun `refused deleted-patient lookups are audited, with IP and user agent and without the phone or e-mail`() {
        val unknownUser = assertThrows<ResponseStatusException> {
            deletedPatientsController.searchDeletedPatients(admin(), DeletedPatientSearchRequest(userId = -1), request())
        }
        assertEquals(404, unknownUser.statusCode.value())
        val search = row("DELETED_PATIENT_SEARCH")
        assertEquals("User not found", search.details["refused"])
        assertEquals(404, search.details["status"])
        assertEquals(-1, search.details["userId"])
        assertEquals("USER_ID", search.details["matchedBy"])
        assertEquals(ip, search.ip)
        assertEquals(marker, search.userAgent)

        assertThrows<ResponseStatusException> {
            deletedPatientsController.searchDeletedPatients(admin(), DeletedPatientSearchRequest(), request())
        }
        assertEquals(400, row("DELETED_PATIENT_SEARCH").details["status"])

        // A search that is served (no match) keeps its row, now with the IP; the phone is not in it.
        deletedPatientsController.searchDeletedPatients(admin(), DeletedPatientSearchRequest(phone = "+998901112233"), request())
        val served = row("DELETED_PATIENT_SEARCH")
        assertEquals(0, served.details["count"])
        assertEquals(ip, served.ip)
        assertFalse(served.details.values.any { it.toString().contains("998901112233") })

        assertThrows<ResponseStatusException> { deletedPatientsController.exportDeletedPatient(admin(), -1, false, request()) }
        val export = row("DELETED_PATIENT_EXPORT")
        assertEquals(-1L, export.entityId)
        assertEquals(404, export.details["status"])

        val withChat = assertThrows<ResponseStatusException> { deletedPatientsController.exportDeletedPatient(admin(), -2, true, request()) }
        assertEquals(400, withChat.statusCode.value())
        val chatRow = row("DELETED_PATIENT_EXPORT")
        assertEquals(-2L, chatRow.entityId)
        assertEquals(true, chatRow.details["includeChat"])
        assertEquals(400, chatRow.details["status"])

        assertThrows<ResponseStatusException> { deletedPatientsController.exportDeletedPatientPdf(admin(), -1, request()) }
        assertEquals(404, row("DELETED_PATIENT_EXPORT_PDF").details["status"])
    }

    // ---------- audit list ----------

    @Test
    fun `the audit list combines its filters, keeps the 30-day default, takes a range and a sort, and names the admin`() {
        val admin = admin()
        val adminUser = admin.adminProfile.user
        val typeA = "TEST_A_$marker"
        val typeB = "TEST_B_$marker"
        val entity = "TEST_ENTITY_$marker"
        auditService.logAction(adminUser, typeA, entity, 2, request = request())
        auditService.logAction(adminUser, typeA, entity, 1, request = request())
        auditService.logAction(adminUser, typeB, entity, 1, request = request())
        auditService.logAction(adminUser, typeA, "OTHER_$marker", 1, request = request())
        auditService.logAction(adminUser, typeA, entity, 9, request = request()) // the old one
        jdbc.update("UPDATE audit_logs SET created_at = now() - interval '60 days' WHERE user_agent = ? AND entity_id = 9", marker)

        val page = PageRequest.of(0, 50)
        fun list(
            adminUserId: Long? = null, entityType: String? = null, entityId: Long? = null, actionType: String? = null,
            from: Instant? = null, to: Instant? = null, pageable: PageRequest = page,
        ) = adminController.getAuditLogs(adminUserId, entityType, entityId, actionType, from, to, pageable).content

        // entityType alone was ignored before; with the 30-day default the old row stays out.
        assertEquals(3, list(entityType = entity).size)
        assertEquals(listOf<Any>(2L, 1L), list(entityType = entity, actionType = typeA).map { it["entityId"] }.sortedByDescending { it as Long })
        assertEquals(2, list(entityType = entity, entityId = 1).size)
        assertEquals(1, list(entityType = entity, entityId = 1, actionType = typeB, adminUserId = adminUser.id).size)
        assertEquals(0, list(entityType = entity, adminUserId = -1).size)

        // The range reaches past 30 days; `to` is exclusive and gets its own 30 days when `from` is missing.
        val now = Instant.now()
        assertEquals(4, list(entityType = entity, from = now.minus(90, ChronoUnit.DAYS)).size)
        assertEquals(1, list(entityType = entity, from = now.minus(90, ChronoUnit.DAYS), to = now.minus(30, ChronoUnit.DAYS)).size)
        assertEquals(listOf<Any>(9L), list(entityType = entity, to = now.minus(45, ChronoUnit.DAYS)).map { it["entityId"] })
        // The admin filter reads all time, as before.
        assertEquals(4, list(entityType = entity, adminUserId = adminUser.id).size)
        assertEquals(400, assertThrows<ResponseStatusException> { list(from = now, to = now.minus(1, ChronoUnit.DAYS)) }.statusCode.value())

        // Sort: a known key orders the page, an unknown one is ignored (newest first).
        assertEquals(
            listOf<Any>(1L, 1L, 2L),
            list(entityType = entity, pageable = PageRequest.of(0, 50, Sort.by("entityId"))).map { it["entityId"] },
        )
        val newestFirst = list(entityType = entity, pageable = PageRequest.of(0, 50, Sort.by("noSuchField")))
        assertEquals(newestFirst.map { it["id"] as Long }.sortedDescending(), newestFirst.map { it["id"] })
        assertEquals(2, adminController.getAuditLogs(null, entity, null, null, null, null, PageRequest.of(1, 2)).totalPages) // paging as it was

        val row = newestFirst.first()
        assertEquals("seed.admin@shifa.dev", row["adminEmail"])
        assertEquals("Seed Admin", row["adminName"])
        assertEquals(adminUser.id, row["adminUserId"])

        val types = adminController.getAuditLogTypes()
        assertTrue(types.actionTypes.containsAll(listOf(typeA, typeB)))
        assertTrue(types.entityTypes.contains(entity))
        assertEquals(types.actionTypes.sorted(), types.actionTypes)

        // What a client sends for `from` and `to` binds as an instant.
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), conversion.convert("2026-11-01T00:00:00Z", Instant::class.java))
    }

    @Test
    fun `the activity list combines user and type and takes a range`() {
        val adminUser = admin().adminProfile.user
        val type = "TEST_ACT_$marker"
        userActivityService.logActivity(adminUser, type, success = false, failureReason = "x", request = request())
        userActivityService.logActivity(adminUser, "TEST_OTHER_$marker", request = request())
        userActivityService.logActivity(adminUser, type, request = request())
        jdbc.update(
            "UPDATE user_activity_logs SET created_at = now() - interval '60 days' WHERE user_agent = ? AND success = false", marker,
        )

        val page = PageRequest.of(0, 50)
        fun list(userId: Long? = null, activityType: String? = null, from: Instant? = null, to: Instant? = null) =
            adminController.getActivityLogs(userId, activityType, from, to, page).content

        assertEquals(1, list(activityType = type).size) // 30-day default
        assertEquals(2, list(activityType = type, from = Instant.now().minus(90, ChronoUnit.DAYS)).size)
        assertEquals(2, list(userId = adminUser.id, activityType = type).size) // the user filter reads all time, and both apply
        assertEquals(0, list(userId = -1, activityType = type).size)
        assertEquals(false, list(activityType = type, to = Instant.now().minus(45, ChronoUnit.DAYS)).single()["success"])
        assertNull(list(activityType = type).single()["adminName"]) // the activity row is unchanged
    }
}
