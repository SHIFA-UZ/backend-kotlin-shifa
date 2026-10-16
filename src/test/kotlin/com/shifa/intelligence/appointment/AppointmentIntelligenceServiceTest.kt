package com.shifa.intelligence.appointment

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.shifa.domain.DoctorProfile
import com.shifa.domain.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AppointmentIntelligenceServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneOffset.UTC)
    private val ranges = ClinicTimeRangeResolver(clock)
    private val patients = FakePatients()
    private val readModel = RecordingReadModel()
    private val service = AppointmentIntelligenceService(clock, ranges, patients, readModel)
    private val doctor = DoctorProfile(
        id = 5,
        user = User(id = 1, passwordHash = "x"),
        firstName = "Ada",
        lastName = "Lovelace",
        timeZone = "UTC",
    )
    private val mapper = jacksonObjectMapper()

    @Test
    fun `text period overrides a wrong model period`() {
        val result = service.execute(
            doctor,
            mapOf("timeRange" to mapOf("period" to "TODAY")),
            emptyList(),
            "How many appointments did I have yesterday?",
            null,
        )
        assertEquals(true, result["supported"])
        assertEquals(5L, readModel.doctorIds.single())
        assertEquals(Instant.parse("2026-10-15T00:00:00Z"), readModel.filters.single().startInclusive)
        assertEquals(Instant.parse("2026-10-16T00:00:00Z"), readModel.filters.single().endExclusive)
        assertNull(readModel.filters.single().patientId)
    }

    @Test
    fun `equivalent yesterday and last week phrases resolve through the service`() {
        val yesterdayPhrases = listOf(
            "How many appointments did I have yesterday?",
            "How many appointments were there yesterday?",
            "Count my appointments for yesterday.",
            "Give me yesterday's appointment total.",
        )
        val yesterdayWindows = yesterdayPhrases.map { phrase ->
            readModel.reset()
            service.execute(doctor, emptyMap(), emptyList(), phrase, null)
            readModel.filters.single().startInclusive to readModel.filters.single().endExclusive
        }
        assertTrue(yesterdayWindows.all { it == yesterdayWindows.first() })

        readModel.reset()
        service.execute(doctor, emptyMap(), emptyList(), "How many appointments last week?", null)
        val week = readModel.filters.single()
        readModel.reset()
        service.execute(doctor, emptyMap(), emptyList(), "during the last 7 days", null)
        val rolling = readModel.filters.single()
        assertEquals(Instant.parse("2026-10-05T00:00:00Z"), week.startInclusive)
        assertEquals(Instant.parse("2026-10-09T10:00:00Z"), rolling.startInclusive)
    }

    @Test
    fun `follow up keeps the period then the status then the list`() {
        val first = service.execute(
            doctor,
            emptyMap(),
            emptyList(),
            "How many appointments do I have next week?",
            null,
        )
        assertEquals(Instant.parse("2026-10-19T00:00:00Z"), readModel.filters.single().startInclusive)
        val firstContext = assistantMessage(first)

        readModel.reset()
        val second = service.execute(
            doctor,
            emptyMap(),
            listOf(firstContext),
            "How many are confirmed?",
            null,
        )
        assertEquals(GroupDimension.STATUS, readModel.groups.single().second)
        assertEquals(Instant.parse("2026-10-19T00:00:00Z"), readModel.groups.single().first.startInclusive)
        assertTrue((second["spokenHint"] as String).contains("confirmed"))
        val secondContext = assistantMessage(second)

        readModel.reset()
        service.execute(
            doctor,
            emptyMap(),
            listOf(firstContext, secondContext),
            "What about last week?",
            null,
        )
        val lastWeek = readModel.groups.single().first
        assertEquals(Instant.parse("2026-10-05T00:00:00Z"), lastWeek.startInclusive)
        assertEquals(Instant.parse("2026-10-12T00:00:00Z"), lastWeek.endExclusive)
        assertEquals(GroupDimension.STATUS, readModel.groups.single().second)
        val thirdContext = assistantMessage(
            mapOf(
                "queryContext" to AppointmentQueryContextCodec.encode(
                    5,
                    IntelligenceRequest(
                        metric = Metric.GROUPED_COUNT,
                        shape = Shape.GROUPED,
                        presentation = Presentation.STATUS_BREAKDOWN,
                        groupBy = GroupDimension.STATUS,
                    ),
                    ranges.resolvePeriod(CalendarPeriod.LAST_WEEK, ZoneOffset.UTC),
                    null,
                ),
            ),
        )

        readModel.reset()
        val listed = service.execute(
            doctor,
            emptyMap(),
            listOf(thirdContext),
            "Show me the unconfirmed ones.",
            null,
        )
        assertEquals(setOf("REQUESTED"), readModel.lists.single().first.statuses)
        assertEquals(Instant.parse("2026-10-05T00:00:00Z"), readModel.lists.single().first.startInclusive)
        assertEquals("patient_appointments", listed["cardType"])
    }

    @Test
    fun `cancelled counts name the scheduled time limitation`() {
        val result = service.execute(
            doctor,
            mapOf("filters" to mapOf("statuses" to listOf("CANCELLED"))),
            emptyList(),
            "How many cancelled appointments last month?",
            null,
        )
        val hint = result["spokenHint"] as String
        assertTrue(hint.contains("currently cancelled"))
        assertTrue(hint.contains("not the time the appointment was cancelled"))
        assertEquals(setOf("CANCELLED"), readModel.filters.single().statuses)
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), readModel.filters.single().startInclusive)
    }

    @Test
    fun `no show is unsupported and does not query`() {
        val result = service.execute(
            doctor,
            mapOf("filters" to mapOf("statuses" to listOf("CANCELLED"))),
            emptyList(),
            "How many no-shows last month?",
            null,
        )
        assertEquals(false, result["supported"])
        assertTrue((result["spokenHint"] as String).contains("no-show"))
        assertTrue(readModel.filters.isEmpty())
    }

    @Test
    fun `model doctor id is rejected`() {
        val result = service.execute(
            doctor,
            mapOf("doctorId" to 99, "timeRange" to mapOf("period" to "YESTERDAY")),
            emptyList(),
            "How many appointments yesterday?",
            null,
        )
        assertEquals(false, result["supported"])
        assertTrue(readModel.filters.isEmpty())
    }

    @Test
    fun `another doctors context and patient id do not widen scope`() {
        val foreign = assistantMessage(
            mapOf(
                "queryContext" to mapOf(
                    "v" to 1,
                    "doctorId" to 9,
                    "metric" to "COUNT",
                    "shape" to "AGGREGATE",
                    "presentation" to "TOTAL",
                    "statuses" to emptyList<String>(),
                    "upcoming" to false,
                    "patientId" to 3,
                    "start" to "2026-10-19T00:00:00Z",
                    "endExclusive" to "2026-10-26T00:00:00Z",
                    "label" to "next week",
                    "kind" to "CALENDAR",
                    "sortDescending" to false,
                ),
            ),
        )
        val result = service.execute(
            doctor,
            mapOf("filters" to mapOf("patientId" to 3)),
            listOf(foreign),
            "How many are confirmed?",
            null,
        )
        assertEquals(false, result["supported"])
        assertTrue(readModel.filters.isEmpty())
        assertTrue(readModel.groups.isEmpty())
    }

    @Test
    fun `inaccessible patient id returns nothing from the schedule`() {
        val result = service.execute(
            doctor,
            mapOf("timeRange" to mapOf("period" to "LAST_WEEK")),
            emptyList(),
            "How many appointments with him last week?",
            focusPatientId = 99L,
        )
        assertEquals(false, result["supported"])
        assertTrue((result["spokenHint"] as String).contains("could not find"))
        assertTrue(readModel.filters.isEmpty())
    }

    @Test
    fun `fresh question drops the previous patient`() {
        val prior = assistantMessage(
            mapOf(
                "queryContext" to AppointmentQueryContextCodec.encode(
                    5,
                    IntelligenceRequest(patientId = 3),
                    ranges.resolvePeriod(CalendarPeriod.NEXT_WEEK, ZoneOffset.UTC),
                    3,
                ),
            ),
        )
        service.execute(
            doctor,
            mapOf("filters" to mapOf("patientId" to 3)),
            listOf(prior),
            "How many appointments did I have yesterday?",
            null,
        )
        assertNull(readModel.filters.single().patientId)
    }

    @Test
    fun `multiple name matches ask which patient and do not count`() {
        patients.byName = mapOf("ana" to listOf(PatientMatch(3, "Ana"), PatientMatch(4, "Ana Kim")))
        val result = service.execute(
            doctor,
            mapOf("filters" to mapOf("patientName" to "Ana")),
            emptyList(),
            "How many appointments with Ana last week?",
            null,
        )
        assertEquals("AMBIGUOUS_PATIENT", result["reason"])
        assertTrue(readModel.filters.isEmpty())
    }

    @Test
    fun `documentation and payment filters are passed through`() {
        service.execute(
            doctor,
            mapOf(
                "filters" to mapOf(
                    "documentation" to "NO_CONSULTATION_NOTE",
                    "paymentStatus" to "UNPAID",
                    "statuses" to listOf("COMPLETED"),
                ),
            ),
            emptyList(),
            "How many completed appointments last month are undocumented and unpaid?",
            null,
        )
        val filter = readModel.filters.single()
        assertEquals(setOf("COMPLETED"), filter.statuses)
        assertEquals(DocumentationFilter.NO_CONSULTATION_NOTE, filter.documentation)
        assertEquals(PaymentSnapshot.PENDING, filter.paymentStatus)
    }

    @Test
    fun `cancellation rate uses past visits and a zero denominator`() {
        readModel.countResult = 0
        val result = service.execute(
            doctor,
            mapOf("metric" to "CANCELLATION_RATE"),
            emptyList(),
            "What is the cancellation rate next week?",
            null,
        )
        assertEquals(false, readModel.filters.any { it.excludeFuture.not() })
        assertTrue(readModel.filters.all { it.excludeFuture })
        assertNull(result["ratePercent"])
        assertTrue((result["spokenHint"] as String).contains("not available"))
    }

    @Test
    fun `collected money refuses a status filter`() {
        val result = service.execute(
            doctor,
            mapOf("metric" to "SUM_COLLECTED", "filters" to mapOf("statuses" to listOf("COMPLETED"))),
            emptyList(),
            "How much did I collect last month?",
            null,
        )
        assertEquals(false, result["supported"])
        assertTrue(readModel.collected.isEmpty())
    }

    private fun assistantMessage(result: Map<String, Any?>): String {
        val json = mapper.writeValueAsString(result["queryContext"])
        return "You have some appointments.\n<appointment_query>$json</appointment_query>"
    }

    private class FakePatients : AppointmentIntelligencePatients {
        var allowed = setOf(3L, 4L)
        var names = mapOf(3L to "Ana", 4L to "Ana Kim")
        var byName: Map<String, List<PatientMatch>> = mapOf("ana" to listOf(PatientMatch(3, "Ana")))

        override fun mayAccess(doctorId: Long, patientId: Long): Boolean =
            doctorId == 5L && patientId in allowed

        override fun findByName(doctorId: Long, name: String): List<PatientMatch> =
            if (doctorId == 5L) byName[name.lowercase()] ?: emptyList() else emptyList()

        override fun nameOf(patientId: Long): String? = names[patientId]
    }

    private class RecordingReadModel : AppointmentIntelligenceReadModel {
        val doctorIds = mutableListOf<Long>()
        val filters = mutableListOf<AppointmentFilter>()
        val groups = mutableListOf<Pair<AppointmentFilter, GroupDimension>>()
        val lists = mutableListOf<Pair<AppointmentFilter, Boolean>>()
        val collected = mutableListOf<Long>()
        var countResult = 4L

        fun reset() {
            doctorIds.clear()
            filters.clear()
            groups.clear()
            lists.clear()
            collected.clear()
        }

        override fun count(doctorId: Long, filter: AppointmentFilter): Long {
            doctorIds += doctorId
            filters += filter
            return countResult
        }

        override fun countDistinctPatients(doctorId: Long, filter: AppointmentFilter): Long = count(doctorId, filter)

        override fun group(doctorId: Long, filter: AppointmentFilter, dimension: GroupDimension): List<CountGroup> {
            doctorIds += doctorId
            groups += filter to dimension
            return listOf(CountGroup("CONFIRMED", 14), CountGroup("REQUESTED", 4))
        }

        override fun list(
            doctorId: Long,
            filter: AppointmentFilter,
            descending: Boolean,
            limit: Int,
        ): List<ListedVisit> {
            doctorIds += doctorId
            lists += filter to descending
            return emptyList()
        }

        override fun consultationCollected(doctorId: Long, start: Instant, end: Instant): List<MoneyByCurrency> {
            collected += doctorId
            return emptyList()
        }

        override fun ledgerCollected(doctorId: Long, start: Instant, end: Instant): List<MoneyByCurrency> = emptyList()

        override fun appointmentValue(doctorId: Long, filter: AppointmentFilter): List<MoneyByCurrency> = emptyList()

        override fun pendingAppointmentValue(doctorId: Long, filter: AppointmentFilter): List<MoneyByCurrency> =
            emptyList()
    }
}
