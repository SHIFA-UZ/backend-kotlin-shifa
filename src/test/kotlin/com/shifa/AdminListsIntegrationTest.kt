package com.shifa

import com.shifa.admin.api.AdminController
import com.shifa.admin.api.AdminDoctorActivityController
import com.shifa.admin.api.dto.DoctorSubscriptionBillingRequest
import com.shifa.admin.application.AdminClinicService
import com.shifa.admin.application.AdminDoctorActivityService
import com.shifa.auth.application.UserManagementService
import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import com.shifa.clinics.application.InvitationKeyService
import com.shifa.clinics.application.InvitationKeyService.KeyStatus
import com.shifa.patients.domain.PatientProfile
import com.shifa.shared.CodedStatusException
import com.shifa.shared.ErrorCode
import com.shifa.shared.api.ErrorHandler
import com.shifa.shared.security.AdminPrincipal
import jakarta.persistence.EntityManager
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.MethodParameter
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableHandlerMethodArgumentResolver
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * The admin lists and their refusals against PostgreSQL with the dev seed: user list filters and stats, invitation
 * key statuses, clinic search and validation, doctor activity order and windows, 404 for unknown ids. Same context as
 * [ApplicationContextTest] (cached). Every test runs in a rolled-back transaction. The HTTP calls go through a
 * standalone MockMvc over the real controllers and [ErrorHandler], with the seeded super admin as the principal.
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
class AdminListsIntegrationTest {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var em: EntityManager
    @Autowired lateinit var tx: org.springframework.transaction.PlatformTransactionManager
    @Autowired lateinit var principals: com.shifa.shared.security.PrincipalResolverService
    @Autowired lateinit var adminController: AdminController
    @Autowired lateinit var activityController: AdminDoctorActivityController
    @Autowired lateinit var errorHandler: ErrorHandler
    @Autowired lateinit var userManagement: UserManagementService
    @Autowired lateinit var users: com.shifa.auth.application.Users
    @Autowired lateinit var patientProfiles: com.shifa.patients.application.PatientProfiles
    @Autowired lateinit var keys: InvitationKeyService
    @Autowired lateinit var clinics: AdminClinicService
    @Autowired lateinit var activity: AdminDoctorActivityService

    companion object {
        @JvmStatic
        @BeforeAll
        fun requirePostgres() = ApplicationContextTest.requirePostgres()

        private const val UNKNOWN_ID = 999_999_999L
    }

    private fun <T> rolledBack(block: () -> T): T =
        TransactionTemplate(tx).execute { status -> block().also { status.setRollbackOnly() } }!!

    private fun admin(): AdminPrincipal {
        val userId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'seed.admin@shifa.dev'", Long::class.java)!!
        return principals.getPrincipalForToken(userId, "ADMIN") as AdminPrincipal
    }

    private fun mockMvc(): MockMvc {
        val principal = admin()
        val asAdmin = object : HandlerMethodArgumentResolver {
            override fun supportsParameter(parameter: MethodParameter) = parameter.parameterType == AdminPrincipal::class.java
            override fun resolveArgument(p: MethodParameter, m: ModelAndViewContainer?, r: NativeWebRequest, f: WebDataBinderFactory?) = principal
        }
        return MockMvcBuilders.standaloneSetup(adminController, activityController)
            .setControllerAdvice(errorHandler)
            .setCustomArgumentResolvers(asAdmin, PageableHandlerMethodArgumentResolver())
            .build()
    }

    private fun marker() = "job3" + UUID.randomUUID().toString().replace("-", "").take(12)

    private fun patient(email: String, createdAt: OffsetDateTime, profile: Boolean, fcmToken: String? = null): User {
        val user = users.save(User(email = email, passwordHash = "x", role = Role.PATIENT, createdAt = createdAt))
        if (profile) patientProfiles.save(PatientProfile(fullName = "Patient $email", user = user, fcmToken = fcmToken))
        return user
    }

    // ---------- users list and stats ----------

    @Test
    fun `user list filters every combination in the query, with the right total and a stable order`() {
        rolledBack {
            val m = marker()
            val now = OffsetDateTime.now()
            val before = userManagement.getUserManagementStats()
            val withDevice = patient("$m-a@t.dev", now.minusDays(3), profile = true, fcmToken = "token")
            val noDevice = patient("$m-b@t.dev", now.minusDays(1), profile = true)
            val noProfile = patient("$m-c@t.dev", now.minusDays(1), profile = false)
            users.save(User(email = "$m-d@t.dev", passwordHash = "x", role = Role.DOCTOR, enabled = false, createdAt = now.minusDays(2)))
            em.flush()
            val firstPage = PageRequest.of(0, 20)

            // search + device: filtered in the query, totals right, also when the page is smaller than the result
            assertEquals(listOf(withDevice.id), userManagement.listUsers(search = m, deviceRegistered = true, pageable = firstPage).content.map { it.id })
            val without = userManagement.listUsers(Role.PATIENT, null, m, false, PageRequest.of(0, 1))
            assertEquals(2, without.totalElements)
            assertEquals(1, without.content.size)
            assertEquals(2, without.totalPages)
            // the device filter is about patient and doctor accounts: the doctor without a profile has no device
            assertEquals(3, userManagement.listUsers(search = m, deviceRegistered = false, pageable = firstPage).totalElements)
            assertEquals(0, userManagement.listUsers(Role.PATIENT, false, m, true, firstPage).totalElements)
            assertEquals(1, userManagement.listUsers(null, false, m, null, firstPage).totalElements)
            // a name on the profile matches too
            assertEquals(2, userManagement.listUsers(search = "patient $m", pageable = firstPage).totalElements)

            // default: newest first, id as the tie-break
            val newestTie = listOf(noDevice.id, noProfile.id).sortedDescending()
            assertEquals(newestTie + withDevice.id, userManagement.listUsers(Role.PATIENT, null, m, null, firstPage).content.map { it.id })
            // sort is honoured with a search and with the device filter alike
            val byIdAsc = PageRequest.of(0, 20, Sort.by("id"))
            assertEquals(listOf(withDevice.id, noDevice.id, noProfile.id), userManagement.listUsers(Role.PATIENT, null, m, null, byIdAsc).content.map { it.id })
            assertEquals(listOf(noDevice.id, noProfile.id), userManagement.listUsers(Role.PATIENT, null, m, false, byIdAsc).content.map { it.id })
            val byEmailDesc = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "email"))
            val emailOrder = userManagement.listUsers(Role.PATIENT, null, m, null, byEmailDesc).content.map { it.id }
            assertEquals(listOf(noProfile.id, noDevice.id, withDevice.id), emailOrder)
            // an unknown sort property falls back to the default order instead of failing
            assertEquals(3, userManagement.listUsers(Role.PATIENT, null, m, null, PageRequest.of(0, 20, Sort.by("nope"))).content.size)

            // stats: the engagement numbers are the list's totals, with and without a profile
            val after = userManagement.getUserManagementStats()
            fun delta(key: String) = (after.getValue(key) as Long) - (before.getValue(key) as Long)
            assertEquals(3, delta("patientAppUsers"))
            assertEquals(1, delta("patientsWithDevice"))
            assertEquals(2, delta("patientAppUsersWithoutDevice"))
            assertEquals(1, delta("doctorsWithoutDevice"))
            assertEquals(after["patientAppUsers"], (after["patientsWithDevice"] as Long) + (after["patientAppUsersWithoutDevice"] as Long))
            assertEquals(after["totalDoctors"], (after["doctorsWithDevice"] as Long) + (after["doctorsWithoutDevice"] as Long))
            assertEquals(after["patientAppUsersWithoutDevice"], userManagement.listUsers(Role.PATIENT, null, null, false, firstPage).totalElements)
            assertEquals(after["patientsWithDevice"], userManagement.listUsers(Role.PATIENT, null, null, true, firstPage).totalElements)
        }
    }

    // ---------- invitation keys ----------

    @Test
    fun `token list filters by status with right totals, and the dashboard counts active keys only`() {
        rolledBack {
            val purpose = marker()
            val activeBefore = adminController.getDashboardStats(admin())["activeTokens"] as Long
            val active = keys.generateKey(expiresInDays = 5, purpose = purpose)
            val expired = keys.generateKey(expiresInDays = 1, purpose = purpose)
            val revoked = keys.generateKey(purpose = purpose)
            val expiredAndRevoked = keys.generateKey(expiresInDays = 1, purpose = purpose)
            keys.revokeKey(revoked.id)
            keys.revokeKey(expiredAndRevoked.id)
            em.flush()
            jdbc.update("UPDATE invitation_keys SET expires_at = now() - interval '1 day' WHERE id IN (?, ?)", expired.id, expiredAndRevoked.id)
            em.clear()

            val page = PageRequest.of(0, 20)
            fun ids(status: KeyStatus?, consumed: Boolean? = null) = keys.findForAdmin(status, consumed, purpose, page).content.map { it.id }
            assertEquals(listOf(active.id), ids(KeyStatus.ACTIVE))
            assertEquals(listOf(expired.id), ids(KeyStatus.EXPIRED))
            assertEquals(listOf(expiredAndRevoked.id, revoked.id), ids(KeyStatus.CONSUMED))
            assertEquals(4, ids(KeyStatus.ALL).size)
            // the older parameter keeps its meaning: unused keys, expired ones included
            assertEquals(listOf(expired.id, active.id), ids(null, consumed = false))
            assertEquals(2, ids(null, consumed = true).size)
            val paged = keys.findForAdmin(KeyStatus.ALL, null, purpose, PageRequest.of(1, 3))
            assertEquals(4, paged.totalElements)
            assertEquals(1, paged.content.size)

            assertEquals(activeBefore + 1, adminController.getDashboardStats(admin())["activeTokens"])

            val mvc = mockMvc()
            mvc.perform(get("/api/admin/tokens").param("status", "expired").param("purpose", purpose))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("EXPIRED"))
            mvc.perform(get("/api/admin/tokens").param("status", "consumed").param("purpose", purpose))
                .andExpect(jsonPath("$.content[0].status").value("CONSUMED"))
                .andExpect(jsonPath("$.content[0].consumedAt").isNotEmpty)
                .andExpect(jsonPath("$.content[0].consumedByUserId").doesNotExist())
            mvc.perform(get("/api/admin/tokens").param("status", "nope")).andExpect(status().isBadRequest)
        }
    }

    @Test
    fun `generate and regenerate validate the days, and regenerate keeps the old validity and notes`() {
        rolledBack {
            val mvc = mockMvc()
            for (days in listOf(-3, 0)) {
                mvc.perform(post("/api/admin/tokens/generate").contentType(MediaType.APPLICATION_JSON).content("""{"expiresInDays":$days}"""))
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.code").value("VALIDATION"))
                    .andExpect(jsonPath("$.errors.expiresInDays").exists())
            }
            // any positive number of days the panel's form accepts passes
            for (days in listOf(7, 9999)) {
                mvc.perform(post("/api/admin/tokens/generate").contentType(MediaType.APPLICATION_JSON).content("""{"expiresInDays":$days}"""))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
            }

            val old = keys.generateKey(expiresInDays = 10, notes = "for Dr. A")
            mvc.perform(post("/api/admin/tokens/${old.id}/regenerate").contentType(MediaType.APPLICATION_JSON).content("""{"expiresInDays":-1}"""))
                .andExpect(status().isBadRequest)
            val kept = keys.regenerateKey(old.id, admin().adminProfile.user)
            val validDays = java.time.Duration.between(OffsetDateTime.now(), kept.expiresAt).toHours() / 24.0
            assertTrue(validDays in 9.9..10.1, "kept validity: $validDays days")
            assertEquals("for Dr. A\nRegenerated from key ${old.keyCode}", kept.notes)
            assertTrue(keys.findById(old.id).get().consumed)
            assertNotNull(keys.regenerateKey(kept.id, admin().adminProfile.user, expiresInDays = 2).expiresAt)
            val forever = keys.generateKey()
            assertNull(keys.regenerateKey(forever.id, admin().adminProfile.user).expiresAt)
        }
    }

    // ---------- clinics ----------

    @Test
    fun `clinics validate the zone and the e-mail, search, and count doctors`() {
        rolledBack {
            val mvc = mockMvc()
            val name = "Clinic ${marker()}"
            fun body(zone: String, email: String) = """{"name":"$name","timeZone":"$zone","email":"$email"}"""
            mvc.perform(post("/api/admin/clinics").contentType(MediaType.APPLICATION_JSON).content(body("Mars/Olympus", "a@b.uz")))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            mvc.perform(post("/api/admin/clinics").contentType(MediaType.APPLICATION_JSON).content(body("Asia/Tashkent", "not an address")))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.errors.email").exists())
            mvc.perform(post("/api/admin/clinics").contentType(MediaType.APPLICATION_JSON).content(body("Asia/Samarkand", "front@clinic.uz")))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.timeZone").value("Asia/Samarkand"))
            val id = jdbc.queryForObject("SELECT id FROM clinics WHERE name = ?", Long::class.java, name)!!
            mvc.perform(put("/api/admin/clinics/$id").contentType(MediaType.APPLICATION_JSON).content(body("Tashkent", "front@clinic.uz")))
                .andExpect(status().isBadRequest)

            mvc.perform(get("/api/admin/clinics").param("search", name.lowercase().drop(3)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].doctorCount").value(0))
            mvc.perform(get("/api/admin/clinics").param("search", "FRONT@clinic"))
                .andExpect(jsonPath("$.content[?(@.id == $id)]").exists())

            // the counts of the whole list equal the per-clinic truth
            val expected = jdbc.queryForList("SELECT clinic_id AS id, count(*) AS n FROM doctor_profiles WHERE clinic_id IS NOT NULL GROUP BY 1")
                .associate { (it["id"] as Number).toLong() to (it["n"] as Number).toInt() }
            assertTrue(expected.isNotEmpty(), "the seed has a clinic with doctors")
            val rows = clinics.listPaged(null, PageRequest.of(0, 500, Sort.by("name"))).content
            rows.forEach { assertEquals(expected[it.id] ?: 0, it.doctorCount, "clinic ${it.id}") }

            // a refused member change names its reason
            val doctorId = jdbc.queryForObject(
                "SELECT id FROM doctor_profiles WHERE clinic_id IS NOT NULL AND clinic_id <> ? LIMIT 1", Long::class.java, id,
            )!!
            mvc.perform(delete("/api/admin/clinics/$id/doctors/$doctorId"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("CLINIC_MEMBER_OTHER_CLINIC"))
                .andExpect(jsonPath("$.message").value("Doctor is not in this clinic"))
        }
    }

    // ---------- doctor activity ----------

    @Test
    fun `doctor activity puts never-active doctors last both ways and ignores future visits`() {
        rolledBack {
            val m = marker()
            // Two doctor accounts without a profile (never active) next to the seeded doctors.
            val a = users.save(User(email = "$m-1@t.dev", passwordHash = "x", role = Role.DOCTOR))
            val b = users.save(User(email = "$m-2@t.dev", passwordHash = "x", role = Role.DOCTOR))
            // A visit in the future for a seeded doctor: not activity yet.
            jdbc.update(
                "UPDATE appointments SET start_at = now() + interval '5 days', end_at = now() + interval '5 days 30 minutes' " +
                    "WHERE id = (SELECT id FROM appointments ORDER BY start_at DESC LIMIT 1)",
            )
            em.flush()
            em.clear()
            val futureDoctor = jdbc.queryForObject("SELECT doctor_id FROM appointments ORDER BY start_at DESC LIMIT 1", Long::class.java)!!

            for (desc in listOf(true, false)) {
                val rows = activity.listPaged(null, null, null, "lastactive", desc, 0, 500).content
                val dates = rows.map { it.lastActiveAt?.let(Instant::parse) }
                assertTrue(dates.any { it == null } && dates.any { it != null })
                assertEquals(dates.filterNotNull().size, dates.indexOfFirst { it == null }, "nulls last, desc=$desc")
                val known = dates.filterNotNull()
                assertEquals(if (desc) known.sortedDescending() else known.sorted(), known)
                assertTrue(known.all { !it.isAfter(Instant.now()) }, "a last-active date in the future")
            }
            // the single-doctor path agrees
            val detail = activity.doctorDetail(futureDoctor, null, null)
            assertTrue(detail.row.lastActiveAt?.let { !Instant.parse(it).isAfter(Instant.now()) } ?: true)

            // accounts without a profile keep doctorId 0 and are told apart by userId
            val orphans = activity.listPaged(null, null, m, "name", false, 0, 25).content
            assertEquals(listOf(0L, 0L), orphans.map { it.doctorId })
            assertEquals(setOf(a.id, b.id), orphans.map { it.userId }.toSet())
            assertTrue(orphans.none { it.hasProfile })
            assertTrue(activity.listPaged(null, null, "seed.doctor1", "name", false, 0, 25).content.single().let { it.hasProfile && it.userId != null })
        }
    }

    @Test
    fun `doctor activity names its windows and refuses too many doctors with its own code`() {
        rolledBack {
            val doctorId = jdbc.queryForObject(
                "SELECT d.id FROM doctor_profiles d JOIN users u ON u.id = d.user_id WHERE u.email = 'seed.doctor1@shifa.dev'", Long::class.java,
            )!!
            val today = LocalDate.now(ZoneOffset.UTC)
            val allTime = activity.doctorDetail(doctorId, null, null)
            assertNull(allTime.rowFrom)
            assertNull(allTime.rowTo)
            assertEquals(today.minusDays(89).toString(), allTime.seriesFrom)
            assertEquals(today.toString(), allTime.seriesTo)
            assertEquals(allTime.seriesFrom, allTime.dailySeries.getValue("appointments").first().date)

            val from = today.minusDays(6)
            val week = activity.doctorDetail(doctorId, from, today)
            assertEquals(listOf(from.toString(), today.toString()), listOf(week.rowFrom, week.rowTo))
            assertEquals(listOf(from.toString(), today.toString()), listOf(week.seriesFrom, week.seriesTo))
            assertEquals(week.row.appointmentsBooked, week.dailySeries.getValue("appointments").sumOf { it.count })

            // the billing answer covers the window it is asked for (all time without dates, as before)
            val windowed = activityController.updateSubscriptionBilling(admin(), doctorId, DoctorSubscriptionBillingRequest(trialPeriodMonths = 3), from, today)
            assertEquals(week.row.copy(trialPeriodMonths = 3, monthsAfterTrial = windowed.monthsAfterTrial, totalDebtUsd = windowed.totalDebtUsd), windowed)
            val plain = activityController.updateSubscriptionBilling(admin(), doctorId, DoctorSubscriptionBillingRequest(trialPeriodMonths = 3), null, null)
            assertEquals(allTime.row.appointmentsBooked, plain.appointmentsBooked)

            val m = marker()
            jdbc.update(
                "INSERT INTO users (email, password_hash, role, enabled, account_status, email_verified, force_password_reset, " +
                    "failed_login_attempts, subscription_tier, created_at) " +
                    "SELECT '$m-' || g || '@t.dev', 'x', 'DOCTOR', true, 'ACTIVE', false, false, 0, 'PREMIUM', now() FROM generate_series(1, 501) g",
            )
            val refused = assertThrows<CodedStatusException> { activity.listPaged(null, null, m, "name", false, 0, 25) }
            assertEquals(ErrorCode.TOO_MANY_DOCTORS, refused.code)
            assertEquals(400, refused.statusCode.value())
            assertEquals(501, refused.extra["doctorCount"])
            // its other refusals keep the plain code
            mockMvc().perform(get("/api/admin/doctors/activity").param("from", "2026-01-01"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            mockMvc().perform(get("/api/admin/doctors/activity").param("search", m))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("TOO_MANY_DOCTORS"))
                .andExpect(jsonPath("$.maxDoctors").value(500))
        }
    }

    // ---------- unknown ids, duplicate admin e-mail ----------

    @Test
    fun `unknown ids on admin routes answer 404 with the API body`() {
        rolledBack {
            val mvc = mockMvc()
            val json = MediaType.APPLICATION_JSON
            listOf(
                get("/api/admin/users/$UNKNOWN_ID"),
                post("/api/admin/users/$UNKNOWN_ID/enable").contentType(json).content("""{"enabled":false}"""),
                post("/api/admin/users/$UNKNOWN_ID/reset-password"),
                post("/api/admin/users/$UNKNOWN_ID/force-logout"),
                post("/api/admin/users/$UNKNOWN_ID/unlock"),
                delete("/api/admin/users/$UNKNOWN_ID"),
                patch("/api/admin/users/$UNKNOWN_ID/subscription-tier").contentType(json).content("""{"tier":"PRO"}"""),
                get("/api/admin/tokens/$UNKNOWN_ID"),
                post("/api/admin/tokens/$UNKNOWN_ID/revoke"),
                post("/api/admin/tokens/$UNKNOWN_ID/regenerate").contentType(json).content("{}"),
                post("/api/admin/doctors/$UNKNOWN_ID/reset-calendar"),
                patch("/api/admin/doctors/$UNKNOWN_ID/sms-reminders-allowed").contentType(json).content("""{"allowed":true}"""),
                get("/api/admin/doctors/$UNKNOWN_ID/activity"),
                get("/api/admin/clinics/$UNKNOWN_ID"),
            ).forEach { request ->
                mvc.perform(request)
                    .andExpect(status().isNotFound)
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").isNotEmpty)
            }
        }
    }

    @Test
    fun `a taken admin e-mail is 409 whatever its case`() {
        rolledBack {
            val mvc = mockMvc()
            for (email in listOf("seed.admin@shifa.dev", "SEED.Admin@Shifa.dev", "  seed.admin@shifa.dev ")) {
                mvc.perform(
                    post("/api/admin/users/create-admin").contentType(MediaType.APPLICATION_JSON)
                        .content("""{"email":"$email","password":"Str0ng!Passw0rd","firstName":"A","lastName":"B"}"""),
                )
                    .andExpect(status().isConflict)
                    .andExpect(jsonPath("$.code").value("CONFLICT"))
                    .andExpect(jsonPath("$.message").value("Email already registered"))
            }
            assertFalse(jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(email) = 'seed.admin@shifa.dev'", Long::class.java)!! > 1)
        }
    }
}
