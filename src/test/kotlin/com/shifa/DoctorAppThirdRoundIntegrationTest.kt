package com.shifa

import com.shifa.clinics.api.ClinicFinanceController
import com.shifa.clinics.api.TreatmentPlanController
import com.shifa.clinics.api.dto.ComposePlanInstallmentsReq
import com.shifa.clinics.api.dto.ComposeTreatmentPlanRequest
import com.shifa.clinics.api.dto.CreatePlanRequest
import com.shifa.clinics.api.dto.InstallmentItemSpecDto
import com.shifa.clinics.api.dto.LineReq
import com.shifa.clinics.api.dto.PaymentReq
import com.shifa.clinics.domain.TreatmentPlan
import com.shifa.clinics.domain.TreatmentPlanPayment
import com.shifa.shared.CodedStatusException
import com.shifa.shared.ErrorCode
import com.shifa.shared.api.IdempotencyFilter
import com.shifa.shared.api.IdempotencyStore
import com.shifa.shared.security.DoctorPrincipal
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.util.UUID

/**
 * The third round of doctor-app asks against PostgreSQL with the dev seed: idempotency keys (BE-13), the transactional
 * plan compose (BE-12), the finance and ledger filters (BE-11, BE-23), document edits (BE-21), the forgot-password
 * pre-check (BE-26) and the follow-up booking guard (BE-20). Same context as [ApplicationContextTest] (cached).
 * Writes run in a rolled-back transaction where they can.
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
class DoctorAppThirdRoundIntegrationTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var tx: org.springframework.transaction.PlatformTransactionManager
    @Autowired lateinit var objectMapper: tools.jackson.databind.ObjectMapper
    @Autowired lateinit var idempotencyStore: IdempotencyStore
    @Autowired lateinit var principals: com.shifa.shared.security.PrincipalResolverService
    @Autowired lateinit var plansController: TreatmentPlanController
    @Autowired lateinit var financeController: ClinicFinanceController
    @Autowired lateinit var documents: com.shifa.documents.application.PatientDocumentService
    @Autowired lateinit var patientProfiles: com.shifa.patients.application.PatientProfiles
    @Autowired lateinit var analytics: com.shifa.doctors.application.DoctorAnalyticsService
    @Autowired lateinit var booking: com.shifa.appointments.application.DoctorAppointmentBookingService
    @Autowired lateinit var fileStorage: com.shifa.shared.storage.FileStorage
    @Autowired lateinit var documentAccess: com.shifa.documents.application.DocumentAccessService
    @Autowired lateinit var planLines: com.shifa.clinics.infrastructure.TreatmentPlanLineRepository

    companion object {
        @JvmStatic
        @BeforeAll
        fun requirePostgres() = ApplicationContextTest.requirePostgres()
    }

    @AfterEach
    fun clearSecurity() = SecurityContextHolder.clearContext()

    private fun doctor1(): DoctorPrincipal {
        val userId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'seed.doctor1@shifa.dev'", Long::class.java)!!
        return principals.getPrincipalForToken(userId, "DOCTOR") as DoctorPrincipal
    }

    private fun clinicId(): Long = jdbc.queryForObject("SELECT id FROM clinics WHERE name = 'Shifa Dev Clinic' ORDER BY id LIMIT 1", Long::class.java)!!
    private fun patientId(name: String): Long =
        jdbc.queryForObject("SELECT id FROM patient_profiles WHERE full_name = ? ORDER BY id LIMIT 1", Long::class.java, name)!!

    private fun <T> rolledBack(block: () -> T): T =
        TransactionTemplate(tx).execute { status -> block().also { status.setRollbackOnly() } }!!

    // ---------- BE-13 ----------

    @Test
    fun `an idempotency key replays the first 2xx answer and runs the request once`() {
        val filter = IdempotencyFilter(idempotencyStore, objectMapper)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(doctor1(), null, emptyList())
        val key = UUID.randomUUID().toString()
        var runs = 0
        var answerStatus = 200
        val chain = FilterChain { req, res ->
            runs++
            val body = req.inputStream.readAllBytes().decodeToString() // the controller still reads the body
            (res as jakarta.servlet.http.HttpServletResponse).status = answerStatus
            res.contentType = "application/json"
            res.writer.write("""{"run":$runs,"echo":${body.length}}""")
        }
        fun call(path: String, body: String, k: String = key): MockHttpServletResponse {
            val request = MockHttpServletRequest("POST", path).apply {
                addHeader(IdempotencyFilter.HEADER, k)
                contentType = MediaType.APPLICATION_JSON_VALUE
                setContent(body.toByteArray())
            }
            return MockHttpServletResponse().also { filter.doFilter(request, it, chain) }
        }

        val first = call("/api/treatment-plans/1/payments", """{"amountMinor":5000}""")
        val replay = call("/api/treatment-plans/1/payments", """{"amountMinor":5000}""")
        assertEquals(1, runs)
        assertEquals(first.contentAsString, replay.contentAsString)
        assertEquals("""{"run":1,"echo":20}""", replay.contentAsString)
        assertEquals("true", replay.getHeader(IdempotencyFilter.REPLAYED_HEADER))

        val reused = call("/api/treatment-plans/1/payments", """{"amountMinor":9999}""")
        assertEquals(409, reused.status)
        assertTrue(reused.contentAsString.contains("IDEMPOTENCY_KEY_REUSED"))
        assertEquals(1, runs)

        // A failed first try is not stored: the retry runs again.
        answerStatus = 400
        val failKey = UUID.randomUUID().toString()
        call("/api/clinics/1/finance/payments", "{}", failKey)
        answerStatus = 200
        call("/api/clinics/1/finance/payments", "{}", failKey)
        assertEquals(3, runs)

        assertEquals(400, call("/api/clinics/1/finance/payments", "{}", "bad key!").status)
        // Not a covered route, or no header: untouched.
        call("/api/treatment-plans/1/status", "{}")
        assertEquals(4, runs)
        jdbc.update("DELETE FROM idempotency_keys WHERE idem_key IN (?, ?)", key, failKey)
    }

    // ---------- BE-12 ----------

    private fun composeRequest(scheduleMinor: List<Long>, status: TreatmentPlan.Status? = TreatmentPlan.Status.ACTIVE) =
        ComposeTreatmentPlanRequest(
            plan = CreatePlanRequest(
                clinicId = clinicId(),
                patientId = patientId("Jasur Aliyev"),
                attendingDoctorId = null,
                title = "Compose test",
                diagnosis = null,
                notes = null,
                paymentReminderDays = null,
            ),
            lines = listOf(
                LineReq(catalogItemId = null, title = "Filling", quantity = 1, unitPriceMinor = 100_000),
                LineReq(catalogItemId = null, title = "Cleaning", quantity = 2, unitPriceMinor = 25_000),
            ),
            initialPayment = PaymentReq(amountMinor = 50_000, method = TreatmentPlanPayment.PaymentMethod.CASH, memo = null),
            installments = ComposePlanInstallmentsReq(
                scheduleItems = scheduleMinor.mapIndexed { i, amount -> InstallmentItemSpecDto(LocalDate.now().plusMonths(i + 1L), amount) },
            ),
            status = status,
        )

    @Test
    fun `compose creates plan, lines, payment, installments and status in one transaction`() {
        val principal = doctor1()
        val detail = rolledBack { plansController.composePlan(principal, composeRequest(listOf(50_000, 50_000))) }
        assertEquals("ACTIVE", detail.summary.status)
        assertEquals(150_000, detail.summary.totalMinor)
        assertEquals(50_000, detail.summary.paidMinor)
        assertEquals(2, detail.lines.size)
        assertEquals(100_000, detail.installmentPlans.single().scheduleRows.sumOf { it.amountMinor })
    }

    @Test
    fun `installments that do not add up roll everything back`() {
        val before = jdbc.queryForObject("SELECT count(*) FROM treatment_plans", Long::class.java)
        val e = assertThrows<CodedStatusException> { plansController.composePlan(doctor1(), composeRequest(listOf(40_000, 40_000))) }
        assertEquals(ErrorCode.INSTALLMENTS_MISMATCH, e.code)
        assertEquals(100_000L, e.extra["outstandingMinor"])
        assertEquals(80_000L, e.extra["scheduledMinor"])
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM treatment_plans", Long::class.java))
    }

    // ---------- BE-11, BE-23, BE-19 ----------

    @Test
    fun `finance lists take a range and the ledger a doctor`() {
        val principal = doctor1()
        val clinicId = clinicId()
        val from = "2020-01-01T00:00:00Z"
        val to = "2100-01-01T00:00:00Z"
        rolledBack {
            financeController.listPayments(principal, clinicId, 0, 50, from, to)
            val records = financeController.listRecords(principal, clinicId, "PAID", 0, 50, from, to)
            assertEquals(0, records["number"])
            assertThrows<IllegalArgumentException> { financeController.listRecords(principal, clinicId, null, 0, 50, from, null) }
            financeController.appointmentLedger(principal, clinicId, null, null, 0, 20, principal.profile.id)
            financeController.appointmentLedger(principal, clinicId, from, to, 0, 20, principal.profile.id)
            // BE-37: a month without a doctor filter answered 500 (DISTINCT with ORDER BY an unselected column).
            financeController.appointmentLedger(principal, clinicId, from, to, 0, 20, null)
            val page = org.springframework.data.domain.PageRequest.of(0, 20)
            val (start, end) = java.time.Instant.parse(from) to java.time.Instant.parse(to)
            planLines.findDistinctLinkedAppointmentIdsForClinicInRange(clinicId, start, end, page)
            planLines.findDistinctLinkedAppointmentIdsForClinicAndPatientsInRange(clinicId, listOf(-1L), start, end, page)
        }
        val today = LocalDate.now()
        analytics.overview(principal.profile, today.minusDays(30), today)
        analytics.consultationTypes(principal.profile, today.minusDays(30), today)
        analytics.engagement(principal.profile, today.minusDays(30), today)
        assertThrows<IllegalArgumentException> { analytics.overview(principal.profile, today, null) }
        assertThrows<IllegalArgumentException> { analytics.overview(principal.profile, today, today.minusDays(1)) }
    }

    // ---------- BE-20 ----------

    @Test
    fun `a follow-up must be an appointment of the same patient`() {
        val principal = doctor1()
        val otherPatientsVisit = jdbc.queryForObject(
            "SELECT a.id FROM appointments a JOIN patient_profiles p ON p.id = a.patient_id WHERE p.full_name <> 'Jasur Aliyev' ORDER BY a.id LIMIT 1",
            Long::class.java,
        )
        val e = assertThrows<IllegalArgumentException> {
            rolledBack {
                booking.book(
                    principal.profile,
                    null,
                    com.shifa.appointments.application.DoctorBookingCommand(
                        patientId = patientId("Jasur Aliyev"),
                        startAt = java.time.Instant.now().plusSeconds(86_400L * 30),
                        slotMinutes = 30,
                        followUpOf = otherPatientsVisit,
                    ),
                )
            }
        }
        assertTrue(e.message!!.contains("followUpOf"))
    }

    // ---------- BE-21 ----------

    @Test
    fun `a doctor renames, recategorises and removes only their own uploads`() {
        val principal = doctor1()
        val doctorId = principal.profile.id
        val patientId = patientId("Jasur Aliyev")
        rolledBack {
            val patient = patientProfiles.findById(patientId).orElseThrow()
            val doc = documents.save(
                com.shifa.documents.domain.PatientDocument(
                    patient = patient, title = "Scan", filePath = "patientdocuments/$patientId/scan.pdf",
                    uploadedByDoctor = principal.profile, category = "APPOINTMENT_NOTE",
                ),
            )
            assertTrue(documents.list(patientId, doctorId).single { it.id == doc.id }.canEdit) // the uploader's
            assertTrue(documents.list(patientId, doctorId).filter { it.creatorType == "PATIENT" }.none { it.canEdit })
            // Clinic staff read as the clinic's doctor but edit nothing (no editor).
            assertTrue(documents.list(patientId, doctorId, editorId = null).none { it.canEdit })
            val renamed = documents.patchByDoctor(patientId, doc.id!!, doctorId, "MRI knee", "MRI")
            assertEquals("MRI knee", renamed.title)
            assertTrue(renamed.canEdit)
            assertTrue(doc.isSharedWithTeam) // MRI is a medical result: shared like an upload with that category
            assertThrows<IllegalArgumentException> { documents.patchByDoctor(patientId, doc.id!!, doctorId, null, "NOT_A_CATEGORY") }

            val bytes = "scan".toByteArray()
            fileStorage.put(doc.filePath, java.io.ByteArrayInputStream(bytes), bytes.size.toLong(), null)
            documents.deleteByDoctor(patientId, doc.id!!, doctorId)
            // The bytes move to a prefix no route serves; the old key goes after the commit (never here: rolled back).
            val retired = "deleted-patientdocuments/$patientId/scan.pdf"
            assertEquals(retired, doc.filePath) // the same managed entity deleteByDoctor changed
            assertTrue(fileStorage.exists(retired))
            fileStorage.delete(retired)
            fileStorage.delete("patientdocuments/$patientId/scan.pdf")
            assertTrue(documents.findById(doc.id!!).isEmpty)
            assertFalse(documents.list(patientId, doctorId).any { it.id == doc.id })
            assertFalse(documents.listForPatientSelf(patientId).any { it.id == doc.id })
            // A removed document takes no access requests.
            assertThrows<IllegalArgumentException> { documentAccess.requestAccess(doc.id!!, doctorId) }

            val patientsOwn = jdbc.queryForObject(
                "SELECT id FROM patient_documents WHERE uploaded_by_patient_profile_id IS NOT NULL ORDER BY id LIMIT 1",
                Long::class.java,
            )
            val forbidden = assertThrows<org.springframework.web.server.ResponseStatusException> {
                documents.deleteByDoctor(
                    jdbc.queryForObject("SELECT patient_id FROM patient_documents WHERE id = ?", Long::class.java, patientsOwn)!!,
                    patientsOwn!!,
                    doctorId,
                )
            }
            assertEquals(403, forbidden.statusCode.value())
        }
    }

    // ---------- BE-26 ----------

    @Test
    fun `the forgot-password code can be checked without using it up`() {
        val email = "seed.doctor1@shifa.dev"
        val code = (100_000..999_999).random().toString()
        jdbc.update(
            "INSERT INTO email_verification_codes (email, code, purpose, verified, attempts, created_at, expires_at) " +
                "VALUES (?, ?, 'FORGOT_PASSWORD', false, 0, now(), now() + interval '10 minutes')",
            email, code,
        )
        try {
            fun check(who: String, what: String) = mockMvc.perform(
                post("/api/auth/verify-forgot-password-otp").contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$who","code":"$what"}"""),
            )
            repeat(2) { check(email, code).andExpect(status().isOk).andExpect(jsonPath("$.valid").value(true)) }
            check(email, "000000").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("EMAIL_OTP_INVALID"))
            check("nobody@example.com", code).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("EMAIL_OTP_INVALID"))
            val row = jdbc.queryForMap("SELECT verified, attempts FROM email_verification_codes WHERE email = ? AND code = ?", email, code)
            assertEquals(false, row["verified"]) // still usable by forgot-password-reset
            assertEquals(1, row["attempts"]) // the wrong code counted
        } finally {
            jdbc.update("UPDATE email_verification_codes SET verified = true WHERE email = ? AND code = ?", email, code)
        }
    }

    // ---------- BE-30 ----------

    @Test
    fun `removing the profile photo clears it`() {
        val principal = doctor1()
        rolledBack {
            principal.profile.avatarUrl = "doctors/test-${principal.profile.id}.jpg"
            assertEquals(204, doctorController.deletePhoto(principal).statusCode.value())
            assertNull(principal.profile.avatarUrl)
        }
    }

    @Autowired lateinit var doctorController: com.shifa.doctors.api.DoctorController

    // ---------- pushes follow the session ----------

    @Autowired lateinit var pushTokenRepo: com.shifa.notifications.infrastructure.DoctorPushTokenRepository

    @Test
    fun `a device rings only while the session that registered it is live`() {
        val doctor = doctor1()
        rolledBack {
            val userId = doctor.profile.user.id
            jdbc.update("DELETE FROM user_sessions WHERE user_id = ?", userId)
            jdbc.update("DELETE FROM doctor_push_tokens WHERE doctor_profile_id = ?", doctor.profile.id)
            fun session(jti: String, expires: String, revoked: Boolean) = jdbc.update(
                "INSERT INTO user_sessions (user_id, token_jti, expires_at, revoked) VALUES (?, ?, now() + ?::interval, ?)",
                userId, jti, expires, revoked,
            )
            fun device(token: String, jti: String?) = jdbc.update(
                "INSERT INTO doctor_push_tokens (doctor_profile_id, token, session_jti) VALUES (?, ?, ?)",
                doctor.profile.id, token, jti,
            )
            session("jti-expired", "-1 hour", false)
            session("jti-revoked", "1 hour", true)
            device("dev-expired", "jti-expired")
            device("dev-revoked", "jti-revoked")
            device("dev-purged", "jti-gone")
            device("dev-old-row", null)
            assertEquals(emptyList<String>(), pushTokenRepo.findLiveTokens(doctor.profile.id))
            assertFalse(pushTokenRepo.doctorHasLiveSession(doctor.profile.id))

            session("jti-live", "1 hour", false)
            device("dev-live", "jti-live")
            assertEquals(setOf("dev-live", "dev-old-row"), pushTokenRepo.findLiveTokens(doctor.profile.id).toSet())
            assertTrue(pushTokenRepo.doctorHasLiveSession(doctor.profile.id))
        }
    }
}
