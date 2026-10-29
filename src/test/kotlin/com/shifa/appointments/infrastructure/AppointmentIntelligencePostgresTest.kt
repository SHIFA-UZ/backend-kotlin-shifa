package com.shifa.appointments.infrastructure

import com.shifa.appointments.domain.AppointmentFilter
import com.shifa.appointments.domain.DocumentationFilter
import com.shifa.appointments.domain.GroupDimension
import com.shifa.appointments.infrastructure.JdbcAppointmentIntelligenceQueryExecutor

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.DriverManager
import java.time.Instant

/**
 * Runs the real appointment SQL against local PostgreSQL from docker-compose.
 * Skips when that database is not reachable. Does not add Testcontainers.
 */
class AppointmentIntelligencePostgresTest {
    @Test
    fun `date bounds status and patient filters do not duplicate joined notes`() {
        assumeTrue(ready, skipReason)
        val october = filter(
            start = "2026-10-01T00:00:00Z",
            end = "2026-11-01T00:00:00Z",
            statuses = setOf("COMPLETED"),
        )
        assertEquals(2L, executor.count(5L, october))
        assertEquals(2L, executor.count(5L, october.copy(patientId = 9L)))
        assertEquals(0L, executor.count(5L, october.copy(patientId = 8L)))
        assertEquals(1L, executor.count(5L, october.copy(documentation = DocumentationFilter.NO_CONSULTATION_NOTE)))
        assertEquals(1L, executor.count(5L, october.copy(documentation = DocumentationFilter.HAS_CONSULTATION_NOTE)))
        // Doctor 7 sees only its own visit (appointment 4), none of doctor 5's.
        assertEquals(1L, executor.count(7L, october))
        assertEquals(0L, executor.count(7L, october.copy(patientId = 8L)))
        val rows = executor.list(5L, october, descending = false, limit = 25)
        assertEquals(listOf(1L, 5L), rows.map { it.appointmentId })
    }

    @Test
    fun `boundary instant is exclusive and another doctor is invisible`() {
        assumeTrue(ready, skipReason)
        val throughEnd = filter(start = "2026-10-01T00:00:00Z", end = "2026-11-01T00:00:00Z")
        assertEquals(3L, executor.count(5L, throughEnd))
        val endingAtFirst = filter(start = "2026-09-01T00:00:00Z", end = "2026-10-01T00:00:00Z")
        assertEquals(0L, executor.count(5L, endingAtFirst))
    }

    @Test
    fun `collected money sums multiple transactions once and keeps currencies apart`() {
        assumeTrue(ready, skipReason)
        val start = Instant.parse("2026-10-01T00:00:00Z")
        val end = Instant.parse("2026-11-01T00:00:00Z")
        val consultation = executor.consultationCollected(5L, start, end).associate { it.currency to it.minor }
        val ledger = executor.ledgerCollected(5L, start, end).associate { it.currency to it.minor }
        assertEquals(3000L, consultation["UZS"])
        assertEquals(1000L, consultation["USD"])
        assertEquals(7400L, ledger["UZS"])
        assertEquals(null, ledger["USD"])
        val totalUzs = (consultation["UZS"] ?: 0L) + (ledger["UZS"] ?: 0L)
        assertEquals(10400L, totalUzs)
        assertEquals(50L, executor.consultationCollected(8L, start, end).filter { it.currency == "UZS" }.sumOf { it.minor })
    }

    @Test
    fun `local day grouping follows the clinic zone across the spring transition`() {
        assumeTrue(ready, skipReason)
        val grouped = executor.group(
            5L,
            filter(start = "2026-03-07T00:00:00Z", end = "2026-03-10T00:00:00Z", zone = "America/New_York"),
            GroupDimension.LOCAL_DAY,
        ).associate { it.key to it.count }
        assertEquals(1L, grouped["2026-03-07"])
        assertEquals(1L, grouped["2026-03-08"])
    }

    companion object {
        private var ready = false
        private var skipReason = "PostgreSQL is not reachable with the local shifa credentials."
        private lateinit var executor: JdbcAppointmentIntelligenceQueryExecutor
        private var adminUrl: String? = null

        @JvmStatic
        @BeforeAll
        fun connect() {
            val user = System.getenv("DB_USERNAME") ?: "shifa"
            val password = System.getenv("DB_PASSWORD") ?: "shifa"
            val ports = listOfNotNull(System.getenv("DB_PORT"), "5433", "5432").distinct()
            DriverManager.setLoginTimeout(3)
            var connected = false
            for (port in ports) {
                val candidate = "jdbc:postgresql://localhost:$port/shifa"
                try {
                    DriverManager.getConnection(candidate, user, password).use { connection ->
                        connection.createStatement().use { statement ->
                            statement.execute("SELECT 1")
                        }
                    }
                    adminUrl = candidate
                    connected = true
                    break
                } catch (ex: Exception) {
                    skipReason = "localhost:$port ${ex.javaClass.simpleName}: ${ex.message}"
                }
            }
            if (!connected || adminUrl == null) {
                // Locally a missing database skips these tests; in CI the workflow provides one, so skipping would hide a failure.
                if (System.getenv("CI") != null) error("CI: $skipReason")
                ready = false
                return
            }
            try {
                DriverManager.getConnection(adminUrl, user, password).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("DROP SCHEMA IF EXISTS shifa_appt_intel_test CASCADE")
                        statement.execute("CREATE SCHEMA shifa_appt_intel_test")
                    }
                }
            } catch (ex: Exception) {
                if (System.getenv("CI") != null) throw ex
                skipReason = ex.message ?: skipReason
                ready = false
                return
            }
            val dataSource = DriverManagerDataSource().apply {
                setUrl("$adminUrl?currentSchema=shifa_appt_intel_test")
                username = user
                this.password = password
            }
            val jdbc = JdbcTemplate(dataSource)
            runScript(jdbc, SCHEMA)
            runScript(jdbc, SEED)
            executor = JdbcAppointmentIntelligenceQueryExecutor(dataSource)
            ready = true
        }

        @JvmStatic
        @AfterAll
        fun dropSchema() {
            if (!ready || adminUrl == null) return
            val user = System.getenv("DB_USERNAME") ?: "shifa"
            val password = System.getenv("DB_PASSWORD") ?: "shifa"
            runCatching {
                DriverManager.getConnection(adminUrl, user, password).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("DROP SCHEMA IF EXISTS shifa_appt_intel_test CASCADE")
                    }
                }
            }
        }

        private fun runScript(jdbc: JdbcTemplate, script: String) {
            script.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { jdbc.execute(it) }
        }

        private fun filter(
            start: String,
            end: String,
            zone: String = "UTC",
            statuses: Set<String> = emptySet(),
        ) = AppointmentFilter(
            startInclusive = Instant.parse(start),
            endExclusive = Instant.parse(end),
            zoneId = zone,
            statuses = statuses,
            now = Instant.parse("2026-10-16T10:00:00Z"),
        )

        private val SCHEMA = """
            CREATE TABLE patient_profiles (
                id BIGINT PRIMARY KEY,
                full_name TEXT NOT NULL
            );
            CREATE TABLE appointments (
                id BIGINT PRIMARY KEY,
                doctor_id BIGINT NOT NULL,
                patient_id BIGINT NOT NULL,
                start_at TIMESTAMPTZ NOT NULL,
                end_at TIMESTAMPTZ NOT NULL,
                location TEXT NOT NULL,
                status TEXT NOT NULL,
                payment_status TEXT NOT NULL,
                payment_amount_minor BIGINT,
                payment_currency VARCHAR(3),
                signature_requested BOOLEAN NOT NULL DEFAULT FALSE,
                patient_signed_at TIMESTAMPTZ
            );
            CREATE TABLE consultation_notes (
                id BIGINT PRIMARY KEY,
                appointment_id BIGINT,
                doctor_id BIGINT
            );
            CREATE TABLE payments (
                id BIGINT PRIMARY KEY,
                kind TEXT NOT NULL,
                status TEXT NOT NULL,
                amount_minor BIGINT NOT NULL,
                currency VARCHAR(3) NOT NULL,
                payee_doctor_id BIGINT,
                paid_at TIMESTAMPTZ,
                appointment_id BIGINT
            );
            CREATE TABLE treatment_plans (
                id BIGINT PRIMARY KEY,
                attending_doctor_id BIGINT
            );
            CREATE TABLE treatment_plan_payments (
                id BIGINT PRIMARY KEY,
                plan_id BIGINT NOT NULL,
                amount_minor BIGINT NOT NULL,
                currency VARCHAR(3) NOT NULL,
                method TEXT NOT NULL,
                recorded_at TIMESTAMPTZ NOT NULL,
                online_payment_id BIGINT,
                linked_appointment_id BIGINT
            );
        """.trimIndent()

        private val SEED = """
            INSERT INTO patient_profiles (id, full_name) VALUES
                (8, 'Bo'),
                (9, 'Ana');
            INSERT INTO appointments (
                id, doctor_id, patient_id, start_at, end_at, location, status, payment_status,
                payment_amount_minor, payment_currency, signature_requested
            ) VALUES
                (1, 5, 9, '2026-10-01T00:00:00Z', '2026-10-01T00:30:00Z', 'Clinic', 'COMPLETED', 'PENDING', 1000, 'UZS', false),
                (2, 5, 9, '2026-11-01T00:00:00Z', '2026-11-01T00:30:00Z', 'Clinic', 'COMPLETED', 'PAID', 1000, 'UZS', false),
                (3, 5, 8, '2026-10-15T00:00:00Z', '2026-10-15T00:30:00Z', 'Clinic', 'CONFIRMED', 'NOT_REQUIRED', NULL, NULL, false),
                (4, 7, 9, '2026-10-15T00:00:00Z', '2026-10-15T00:30:00Z', 'Clinic', 'COMPLETED', 'PAID', 1000, 'UZS', false),
                (5, 5, 9, '2026-10-20T00:00:00Z', '2026-10-20T00:30:00Z', 'Video Consultation', 'COMPLETED', 'PENDING', 2000, 'UZS', true),
                (6, 5, 9, '2026-03-08T04:30:00Z', '2026-03-08T05:00:00Z', 'Clinic', 'COMPLETED', 'NOT_REQUIRED', NULL, NULL, false),
                (7, 5, 9, '2026-03-08T05:00:00Z', '2026-03-08T05:30:00Z', 'Clinic', 'COMPLETED', 'NOT_REQUIRED', NULL, NULL, false);
            INSERT INTO consultation_notes (id, appointment_id, doctor_id) VALUES
                (1, 1, 5),
                (2, 1, 5);
            INSERT INTO payments (id, kind, status, amount_minor, currency, payee_doctor_id, paid_at, appointment_id) VALUES
                (1, 'CONSULTATION', 'PAID', 3000, 'UZS', 5, '2026-10-05T00:00:00Z', 1),
                (2, 'TREATMENT_PLAN', 'PAID', 5000, 'UZS', 5, '2026-10-06T00:00:00Z', NULL),
                (3, 'CONSULTATION', 'PAID', 1000, 'USD', 5, '2026-10-07T00:00:00Z', 1),
                (4, 'CONSULTATION', 'PAID', 50, 'UZS', 8, '2026-10-07T00:00:00Z', NULL);
            INSERT INTO treatment_plans (id, attending_doctor_id) VALUES
                (1, 5),
                (2, 8);
            INSERT INTO treatment_plan_payments (
                id, plan_id, amount_minor, currency, method, recorded_at, online_payment_id, linked_appointment_id
            ) VALUES
                (1, 1, 5000, 'UZS', 'CLICK', '2026-10-06T00:00:00Z', 2, 1),
                (2, 1, 2000, 'UZS', 'CASH', '2026-10-08T00:00:00Z', NULL, 1),
                (3, 1, 400, 'UZS', 'CASH', '2026-10-09T00:00:00Z', NULL, 1),
                (4, 1, 999, 'UZS', 'WAIVED', '2026-10-08T00:00:00Z', NULL, 1),
                (5, 2, 7000, 'UZS', 'CASH', '2026-10-08T00:00:00Z', NULL, NULL);
        """.trimIndent()
    }
}
