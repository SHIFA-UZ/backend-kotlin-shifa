package com.shifa.service

import com.shifa.domain.DoctorProfile
import com.shifa.domain.User
import com.shifa.intelligence.appointment.AppointmentActionService
import com.shifa.intelligence.appointment.AppointmentIntelligenceService
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.ClinicVisitPatternRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.repo.PatientVisitFactRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Instant
import java.time.LocalDate

class DoctorMorningToolDispatchTest {
    private val appointments = mock(AppointmentRepository::class.java)
    private val patients = mock(PatientProfileRepository::class.java)
    private val slots = mock(PatientDaySlotsService::class.java)
    private val intelligence = mock(AppointmentIntelligenceService::class.java)
    private val tools = DoctorMorningToolService(
        appointments,
        mock(PatientBriefingService::class.java),
        mock(PatientVisitFactRepository::class.java),
        mock(ClinicVisitPatternRepository::class.java),
        patients,
        slots,
        mock(PatientDocumentService::class.java),
        intelligence,
        mock(AppointmentActionService::class.java),
    )
    private val doctor = DoctorProfile(
        id = 5,
        user = User(id = 1, passwordHash = "x"),
        firstName = "Ada",
        lastName = "Lovelace",
        timeZone = "Asia/Tashkent",
    )

    @Test
    fun `today schedule and free slots stay on the existing tools`() {
        `when`(appointments.findForDay(anyLong(), anyInstant(), anyInstant())).thenReturn(emptyList())
        `when`(slots.availableSlotsForDay(anyDoctor(), anyDate(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
            .thenReturn(emptyList())

        val schedule = tools.execute(doctor, "get_today_schedule", emptyMap())
        assertEquals("schedule", schedule["cardType"])
        verify(appointments).findForDay(anyLong(), anyInstant(), anyInstant())

        val slotsResult = tools.execute(doctor, "list_free_slots", mapOf("date" to "today"))
        assertEquals("free_slots", slotsResult["cardType"])
        verifyNoInteractions(intelligence)
    }

    @Test
    fun `analytics tool is dispatched to the intelligence service`() {
        `when`(
            intelligence.execute(
                anyDoctor(),
                anyArgs(),
                anyStrings(),
                anyText(),
                org.mockito.ArgumentMatchers.any(),
                anyIds(),
                org.mockito.ArgumentMatchers.any(),
            ),
        ).thenReturn(mapOf("spokenHint" to "ok", "count" to 2))

        val result = tools.execute(
            doctor,
            "query_appointment_intelligence",
            mapOf("timeRange" to mapOf("expression" to "yesterday")),
            priorAssistantMessages = listOf("earlier"),
            latestUserText = "How many appointments yesterday?",
            focusPatientId = null,
            searchedPatientIds = emptySet(),
        )
        assertEquals(2, result["count"])
        verify(intelligence).execute(
            anyDoctor(),
            anyArgs(),
            anyStrings(),
            anyText(),
            org.mockito.ArgumentMatchers.any(),
            anyIds(),
            org.mockito.ArgumentMatchers.any(),
        )
    }

    @Test
    fun `in the future and from tomorrow list every upcoming visit, not one day or week`() {
        val patient = com.shifa.domain.PatientProfile(id = 59L, fullName = "Botir Muzrabov")
        `when`(patients.findById(59L)).thenReturn(java.util.Optional.of(patient))
        val zone = java.time.ZoneId.of("Asia/Tashkent")
        val today = LocalDate.now(zone)
        fun visit(id: Long, daysAhead: Long, status: com.shifa.domain.Appointment.Status = com.shifa.domain.Appointment.Status.CONFIRMED) =
            com.shifa.domain.Appointment(
                id = id,
                doctor = doctor,
                patient = patient,
                startAt = today.plusDays(daysAhead).atTime(10, 0).atZone(zone).toInstant(),
                endAt = today.plusDays(daysAhead).atTime(10, 30).atZone(zone).toInstant(),
                location = "Clinic",
                status = status,
            )
        `when`(appointments.findByPatientIdAndDoctorIdOrderByStartAtDesc(59L, 5L)).thenReturn(
            listOf(visit(1, 0), visit(2, 2), visit(3, 9), visit(4, 40), visit(5, 3, com.shifa.domain.Appointment.Status.CANCELLED)),
        )

        val upcoming = tools.execute(doctor, "list_patient_appointments", mapOf("patientId" to 59L, "date" to "upcoming"))
        assertEquals(4, upcoming["count"])
        assertEquals("Upcoming (from ${today.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))})", upcoming["rangeLabel"])
        assertEquals(true, (upcoming["spokenHint"] as String).startsWith("You have 4 appointments with Botir Muzrabov coming up"))

        val fromTomorrow = tools.execute(doctor, "list_patient_appointments", mapOf("patientId" to 59L, "date" to "from tomorrow onwards"))
        assertEquals(3, fromTomorrow["count"])
        assertEquals(true, (fromTomorrow["spokenHint"] as String).contains("from tomorrow onwards"))

        val afterToday = tools.execute(doctor, "list_patient_appointments", mapOf("patientId" to 59L, "date" to "after today"))
        assertEquals(3, afterToday["count"])
    }

    private fun anyInstant(): Instant =
        org.mockito.ArgumentMatchers.any(Instant::class.java) ?: Instant.EPOCH

    private fun anyDate(): LocalDate =
        org.mockito.ArgumentMatchers.any(LocalDate::class.java) ?: LocalDate.of(2026, 1, 1)

    private fun anyDoctor(): DoctorProfile =
        org.mockito.ArgumentMatchers.any(DoctorProfile::class.java) ?: doctor

    private fun anyArgs(): Map<String, Any?> =
        org.mockito.ArgumentMatchers.any<Map<String, Any?>>() ?: emptyMap()

    private fun anyStrings(): List<String> =
        org.mockito.ArgumentMatchers.any<List<String>>() ?: emptyList()

    private fun anyText(): String =
        org.mockito.ArgumentMatchers.any(String::class.java) ?: ""

    private fun anyIds(): Set<Long> =
        org.mockito.ArgumentMatchers.any<Set<Long>>() ?: emptySet()
}
