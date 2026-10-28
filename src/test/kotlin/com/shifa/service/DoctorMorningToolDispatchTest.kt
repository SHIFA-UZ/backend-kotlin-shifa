package com.shifa.service

import com.shifa.domain.DoctorProfile
import com.shifa.domain.User
import com.shifa.intelligence.appointment.AppointmentActionService
import com.shifa.intelligence.appointment.AppointmentIntelligenceService
import com.shifa.intelligence.appointment.IntelligenceBookingWorkflowService
import com.shifa.intelligence.appointment.IntelligenceToolReferenceService
import com.shifa.intelligence.appointment.BookingWorkflowStatus
import com.shifa.intelligence.appointment.IntelligenceBookingWorkflow
import com.shifa.intelligence.appointment.PendingPatientCandidate
import com.shifa.intelligence.appointment.PendingQuestionType
import com.shifa.intelligence.appointment.TrustedAppointmentReference
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.ClinicVisitPatternRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.repo.PatientVisitFactRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import java.time.Instant
import java.time.LocalDate
import java.time.Clock
import java.util.UUID

class DoctorMorningToolDispatchTest {
    private val appointments = mock(AppointmentRepository::class.java)
    private val patients = mock(PatientProfileRepository::class.java)
    private val slots = mock(PatientDaySlotsService::class.java)
    private val intelligence = mock(AppointmentIntelligenceService::class.java)
    private val bookingWorkflows = mock(IntelligenceBookingWorkflowService::class.java)
    private val toolReferences = mock(IntelligenceToolReferenceService::class.java)
    private val actions = mock(AppointmentActionService::class.java)
    private val fixedClock = Clock.fixed(Instant.parse("2026-10-20T04:00:00Z"), java.time.ZoneOffset.UTC)
    private val tools = DoctorMorningToolService(
        appointments,
        mock(PatientBriefingService::class.java),
        mock(PatientVisitFactRepository::class.java),
        mock(ClinicVisitPatternRepository::class.java),
        patients,
        slots,
        mock(PatientDocumentService::class.java),
        intelligence,
        actions,
        bookingWorkflows,
        toolReferences,
        fixedClock,
    )

    @Test
    fun `booking confirmation is delegated to the persistent workflow`() {
        `when`(bookingWorkflows.confirm(doctor, "chat_1")).thenReturn(
            mapOf("workflowStatus" to "COMPLETED", "booked" to true),
        )

        val result = tools.execute(
            doctor = doctor,
            toolName = "manage_booking_workflow",
            args = mapOf("turnType" to "CONFIRM"),
            conversationId = "chat_1",
        )

        assertEquals(true, result["booked"])
        verify(bookingWorkflows).confirm(doctor, "chat_1")
    }

    @Test
    fun `affirmative patient answer resolves pending candidate without repeating search`() {
        val pending = pendingPatientWorkflow()
        val resolved = pending.copy(
            patientId = 30,
            patientName = "Franco Herselman",
            pendingQuestionType = null,
            pendingQuestionJson = null,
            pendingQuestionToken = null,
            version = 2,
        )
        `when`(bookingWorkflows.active(5, "chat_patient")).thenReturn(pending)
        `when`(bookingWorkflows.pendingPatientCandidates(pending)).thenReturn(
            listOf(PendingPatientCandidate(30, "Franco Herselman")),
        )
        `when`(bookingWorkflows.resolvePendingPatient(5, "chat_patient", 30)).thenReturn(resolved)
        `when`(bookingWorkflows.saveDetails(doctor, "chat_patient", 30, "Franco Herselman", null, null))
            .thenReturn(resolved)

        val result = tools.execute(
            doctor = doctor,
            toolName = "manage_booking_workflow",
            args = mapOf("turnType" to "CONFIRM"),
            conversationId = "chat_patient",
            latestUserText = "Ha shunday.",
        )

        assertEquals(30L, result["patientId"])
        assertEquals("COLLECTING_DETAILS", result["workflowStatus"])
        verify(bookingWorkflows).resolvePendingPatient(5, "chat_patient", 30)
        verifyNoInteractions(patients)
    }

    @Test
    fun `negative patient answer clears candidate without selecting it`() {
        val pending = pendingPatientWorkflow()
        `when`(bookingWorkflows.active(5, "chat_patient")).thenReturn(pending)
        `when`(bookingWorkflows.pendingPatientCandidates(pending)).thenReturn(
            listOf(PendingPatientCandidate(30, "Franco Herselman")),
        )

        val result = tools.execute(
            doctor = doctor,
            toolName = "manage_booking_workflow",
            args = mapOf("turnType" to "REJECT"),
            conversationId = "chat_patient",
        )

        assertEquals("COLLECTING_DETAILS", result["workflowStatus"])
        verify(bookingWorkflows).clearPendingPatient(5, "chat_patient", true)
        verifyNoInteractions(patients)
    }

    @Test
    fun `patient correction clears stale candidate and resolves the corrected name`() {
        val pending = pendingPatientWorkflow()
        val cleared = pending.copy(
            patientName = null,
            pendingQuestionType = null,
            pendingQuestionJson = null,
            pendingQuestionToken = null,
            version = 2,
        )
        val corrected = com.shifa.domain.PatientProfile(id = 44L, fullName = "Alice Smith")
        `when`(bookingWorkflows.active(5, "chat_patient")).thenReturn(pending)
        `when`(bookingWorkflows.pendingPatientCandidates(pending)).thenReturn(
            listOf(PendingPatientCandidate(30, "Franco Herselman")),
        )
        `when`(bookingWorkflows.clearPendingPatient(5, "chat_patient", true)).thenReturn(cleared)
        `when`(
            patients.findClinicRosterScopedToDoctor(
                5L,
                "Alice Smith",
                org.springframework.data.domain.PageRequest.of(0, 8),
            ),
        ).thenReturn(PageImpl(listOf(corrected)))

        val result = tools.execute(
            doctor = doctor,
            toolName = "manage_booking_workflow",
            args = mapOf("turnType" to "CORRECT_SLOT", "patientName" to "Alice Smith"),
            conversationId = "chat_patient",
        )

        assertEquals(44L, result["patientId"])
        verify(bookingWorkflows).clearPendingPatient(5, "chat_patient", true)
        verify(patients).findClinicRosterScopedToDoctor(
            5L,
            "Alice Smith",
            org.springframework.data.domain.PageRequest.of(0, 8),
        )
    }

    @Test
    fun `multiple trusted schedule matches ask for clarification`() {
        val first = trustedReference(1217L, "10:00")
        val second = trustedReference(1218L, "11:00")
        `when`(appointments.findByDoctorIdAndStartAtBetween(anyLong(), anyInstant(), anyInstant()))
            .thenReturn(emptyList())
        `when`(
            toolReferences.resolveAppointment(
                5L, "chat_schedule", null, "Franco Herselman", null, null,
            ),
        ).thenReturn(listOf(first, second))

        val result = tools.execute(
            doctor = doctor,
            toolName = "propose_reschedule",
            args = mapOf("sourcePatientName" to "Franco Herselman", "date" to "tomorrow", "time" to "12:00"),
            conversationId = "chat_schedule",
        )

        assertEquals("AWAITING_CLARIFICATION", result["workflowStatus"])
        assertEquals(2, (result["appointments"] as List<*>).size)
    }

    @Test
    fun `untrusted cross doctor appointment id is denied without loading it`() {
        `when`(appointments.findByDoctorIdAndStartAtBetween(anyLong(), anyInstant(), anyInstant()))
            .thenReturn(emptyList())
        `when`(
            toolReferences.resolveAppointment(5L, "chat_schedule", 999L, null, null, null),
        ).thenReturn(emptyList())

        val result = tools.execute(
            doctor = doctor,
            toolName = "propose_reschedule",
            args = mapOf("appointmentId" to 999L, "date" to "tomorrow", "time" to "12:00"),
            conversationId = "chat_schedule",
        )

        assertEquals("Appointment reference is not available", result["error"])
        verify(appointments, org.mockito.Mockito.never()).findById(999L)
    }

    @Test
    fun `trusted schedule reference creates reschedule proposal without changing appointment`() {
        val patient = com.shifa.domain.PatientProfile(id = 30L, fullName = "Franco Herselman")
        val appointment = com.shifa.domain.Appointment(
            id = 1217L,
            doctor = doctor,
            patient = patient,
            startAt = Instant.parse("2026-10-20T05:00:00Z"),
            endAt = Instant.parse("2026-10-20T05:30:00Z"),
            location = "Clinic",
            status = com.shifa.domain.Appointment.Status.CONFIRMED,
        )
        val trusted = TrustedAppointmentReference(
            appointmentId = 1217L,
            patientId = 30L,
            patientName = "Franco Herselman",
            localDate = LocalDate.parse("2026-10-20"),
            localTime = java.time.LocalTime.parse("10:00"),
            startAt = appointment.startAt,
            status = "CONFIRMED",
        )
        `when`(appointments.findByDoctorIdAndStartAtBetween(anyLong(), anyInstant(), anyInstant()))
            .thenReturn(listOf(appointment))
        `when`(
            toolReferences.resolveAppointment(
                5L,
                "chat_schedule",
                null,
                "Franko Herselman",
                LocalDate.parse("2026-10-20"),
                java.time.LocalTime.parse("10:00"),
            ),
        ).thenReturn(listOf(trusted))
        `when`(toolReferences.latestSchedule(5L, "chat_schedule")).thenReturn(listOf(trusted))
        `when`(
            slots.availableSlotsForDay(
                doctor,
                LocalDate.parse("2026-10-21"),
                null,
                1217L,
            ),
        ).thenReturn(
            listOf(
                PatientDaySlotsService.AvailableSlotDto(
                    startAt = "2026-10-21T07:00:00Z",
                    endAt = "2026-10-21T07:30:00Z",
                    slotMinutes = 30,
                ),
            ),
        )
        `when`(
            bookingWorkflows.attachRescheduleProposal(anyDoctor(), anyText(), anyArgs()),
        ).thenAnswer { it.getArgument(2) }

        val result = tools.execute(
            doctor = doctor,
            toolName = "propose_reschedule",
            args = mapOf(
                "sourcePatientName" to "Franko Herselman",
                "sourceDate" to "2026-10-20",
                "sourceTime" to "10:00",
                "date" to "tomorrow",
                "time" to "12:00",
            ),
            conversationId = "chat_schedule",
            latestUserText = "Move Franco's 10:00 visit to tomorrow at 12:00",
        )

        assertEquals(1217L, result["appointmentId"])
        assertEquals("2026-10-21", result["date"])
        assertEquals("12:00", (result["preferredSlot"] as Map<*, *>)["startLocal"])
        verify(appointments, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `uzbek follow-up resolves the listed 10am visit without model ids`() {
        val patient = com.shifa.domain.PatientProfile(id = 30L, fullName = "Franco Herselman")
        val appointment = com.shifa.domain.Appointment(
            id = 1217L,
            doctor = doctor,
            patient = patient,
            startAt = Instant.parse("2026-10-20T05:00:00Z"),
            endAt = Instant.parse("2026-10-20T05:30:00Z"),
            location = "Clinic",
            status = com.shifa.domain.Appointment.Status.CONFIRMED,
        )
        val trusted = trustedReference(1217L, "10:00")
        `when`(appointments.findByDoctorIdAndStartAtBetween(anyLong(), anyInstant(), anyInstant()))
            .thenReturn(listOf(appointment))
        `when`(
            toolReferences.resolveAppointment(
                anyLong(),
                nullableText(),
                nullableLong(),
                nullableText(),
                nullableDate(),
                nullableTime(),
            ),
        ).thenReturn(listOf(trusted))
        `when`(toolReferences.latestSchedule(5L, "chat_schedule")).thenReturn(listOf(trusted))
        `when`(
            slots.availableSlotsForDay(doctor, LocalDate.parse("2026-10-21"), null, 1217L),
        ).thenReturn(
            listOf(
                PatientDaySlotsService.AvailableSlotDto(
                    startAt = "2026-10-21T07:00:00Z",
                    endAt = "2026-10-21T07:30:00Z",
                    slotMinutes = 30,
                ),
            ),
        )
        `when`(
            bookingWorkflows.attachRescheduleProposal(anyDoctor(), anyText(), anyArgs()),
        ).thenAnswer { it.getArgument(2) }

        val result = tools.execute(
            doctor = doctor,
            toolName = "propose_reschedule",
            args = emptyMap(),
            conversationId = "chat_schedule",
            latestUserText =
                "Tushunarli. Demak, 10:00 dagi Franko Herselman bilan qilingan uchrashuvni ertaga ko'chiring. Ertaga 12:00 ga.",
        )

        assertEquals(1217L, result["appointmentId"])
        assertEquals(30L, result["patientId"])
        assertEquals("2026-10-21", result["date"])
        assertEquals("12:00", result["requestedTime"])
        assertEquals(false, result["autoConfirm"])
        verify(appointments, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `booking alternatives drop past midnight slots and rank near the requested time`() {
        val patient = com.shifa.domain.PatientProfile(id = 30L, fullName = "Franco Herselman")
        `when`(patients.findById(30L)).thenReturn(java.util.Optional.of(patient))
        `when`(slots.availableSlotsForDay(doctor, LocalDate.parse("2026-10-20"), null, null)).thenReturn(
            listOf(
                PatientDaySlotsService.AvailableSlotDto(
                    startAt = "2026-10-19T19:00:00Z",
                    endAt = "2026-10-19T19:30:00Z",
                    slotMinutes = 30,
                ),
                PatientDaySlotsService.AvailableSlotDto(
                    startAt = "2026-10-20T03:00:00Z",
                    endAt = "2026-10-20T03:30:00Z",
                    slotMinutes = 30,
                ),
                PatientDaySlotsService.AvailableSlotDto(
                    startAt = "2026-10-20T05:00:00Z",
                    endAt = "2026-10-20T05:30:00Z",
                    slotMinutes = 30,
                ),
                PatientDaySlotsService.AvailableSlotDto(
                    startAt = "2026-10-20T08:00:00Z",
                    endAt = "2026-10-20T08:30:00Z",
                    slotMinutes = 30,
                ),
                PatientDaySlotsService.AvailableSlotDto(
                    startAt = "2026-10-20T19:00:00Z",
                    endAt = "2026-10-20T19:30:00Z",
                    slotMinutes = 30,
                ),
            ),
        )

        val result = tools.execute(
            doctor,
            "propose_appointment",
            mapOf("patientId" to 30L, "date" to "2026-10-20", "time" to "13:14"),
        )
        @Suppress("UNCHECKED_CAST")
        val presented = result["slots"] as List<Map<String, Any?>>

        assertEquals(null, result["preferredSlot"])
        assertTrue(presented.none { it["startLocal"] == "00:00" })
        assertEquals("13:00", presented.first()["startLocal"])
    }

    @Test
    fun `same day morning visit can still be proposed after its start time`() {
        val afternoonTools = DoctorMorningToolService(
            appointments,
            mock(PatientBriefingService::class.java),
            mock(PatientVisitFactRepository::class.java),
            mock(ClinicVisitPatternRepository::class.java),
            patients,
            slots,
            mock(PatientDocumentService::class.java),
            intelligence,
            mock(AppointmentActionService::class.java),
            bookingWorkflows,
            toolReferences,
            Clock.fixed(Instant.parse("2026-10-20T10:32:00Z"), java.time.ZoneOffset.UTC),
        )
        val patient = com.shifa.domain.PatientProfile(id = 30L, fullName = "Franco Herselman")
        val appointment = com.shifa.domain.Appointment(
            id = 1217L,
            doctor = doctor,
            patient = patient,
            startAt = Instant.parse("2026-10-20T05:00:00Z"),
            endAt = Instant.parse("2026-10-20T05:30:00Z"),
            location = "Clinic",
            status = com.shifa.domain.Appointment.Status.CONFIRMED,
        )
        val trusted = trustedReference(1217L, "10:00")
        `when`(appointments.findByDoctorIdAndStartAtBetween(anyLong(), anyInstant(), anyInstant()))
            .thenReturn(listOf(appointment))
        `when`(
            toolReferences.resolveAppointment(
                anyLong(),
                nullableText(),
                nullableLong(),
                nullableText(),
                nullableDate(),
                nullableTime(),
            ),
        ).thenReturn(listOf(trusted))
        `when`(toolReferences.latestSchedule(5L, "chat_afternoon")).thenReturn(listOf(trusted))
        `when`(
            slots.availableSlotsForDay(doctor, LocalDate.parse("2026-10-21"), null, 1217L),
        ).thenReturn(
            listOf(
                PatientDaySlotsService.AvailableSlotDto(
                    startAt = "2026-10-21T07:00:00Z",
                    endAt = "2026-10-21T07:30:00Z",
                    slotMinutes = 30,
                ),
            ),
        )
        `when`(
            bookingWorkflows.attachRescheduleProposal(anyDoctor(), anyText(), anyArgs()),
        ).thenAnswer { it.getArgument(2) }

        val result = afternoonTools.execute(
            doctor = doctor,
            toolName = "propose_reschedule",
            args = emptyMap(),
            conversationId = "chat_afternoon",
            latestUserText =
                "Tushunarli. Demak, 10:00 dagi Franko Herselman bilan qilingan uchrashuvni ertaga ko'chiring. Ertaga 12:00 ga.",
        )

        assertEquals(1217L, result["appointmentId"])
        assertEquals("2026-10-21", result["date"])
        assertEquals(null, result["error"])
    }
    private val doctor = DoctorProfile(
        id = 5,
        user = User(id = 1, passwordHash = "x"),
        firstName = "Ada",
        lastName = "Lovelace",
        timeZone = "Asia/Tashkent",
    )

    @Test
    fun `trusted schedule reference opens appointment 1217 without booking`() {
        val trusted = trustedReference(1217L, "10:00")
        `when`(
            toolReferences.resolveAppointment(
                anyLong(), nullableText(), nullableLong(), nullableText(), nullableDate(), nullableTime(),
            ),
        ).thenReturn(listOf(trusted))
        `when`(
            actions.openAppointment(
                anyDoctor(),
                anyText(),
                nullableLong(),
                nullableLong(),
                anyText(),
                org.mockito.ArgumentMatchers.anyBoolean(),
            ),
        ).thenReturn(mapOf("cardType" to "open_appointment", "appointmentId" to 1217L))

        val result = tools.execute(
            doctor = doctor,
            toolName = "open_appointment",
            args = mapOf("patientId" to 30L, "patientName" to "Franco Herselman"),
            conversationId = "chat_schedule",
            latestUserText = "Ha, aynan shunday.",
        )

        assertEquals(1217L, result["appointmentId"])
        verify(slots, org.mockito.Mockito.never()).availableSlotsForDay(
            anyDoctor(), anyDate(), nullableLong(), nullableLong(),
        )
    }

    @Test
    fun `two listed visits ask which one to open`() {
        `when`(
            toolReferences.resolveAppointment(
                anyLong(), nullableText(), nullableLong(), nullableText(), nullableDate(), nullableTime(),
            ),
        ).thenReturn(listOf(trustedReference(1217L, "10:00"), trustedReference(1216L, "08:00")))

        val result = tools.execute(
            doctor = doctor,
            toolName = "open_appointment",
            args = mapOf("patientId" to 30L, "patientName" to "Franco Herselman"),
            conversationId = "chat_schedule",
            latestUserText = "Ha, aynan shunday.",
        )

        assertEquals("AWAITING_CLARIFICATION", result["status"])
        assertTrue((result["spokenHint"] as String).contains("open"))
        verify(actions, org.mockito.Mockito.never()).openAppointment(
            anyDoctor(), anyText(), nullableLong(), nullableLong(), anyText(), org.mockito.ArgumentMatchers.anyBoolean(),
        )
    }

    @Test
    fun `documentation for the 10am visit does not query booking slots`() {
        val trusted = trustedReference(1217L, "10:00")
        `when`(
            toolReferences.resolveAppointment(
                anyLong(), nullableText(), nullableLong(), nullableText(), nullableDate(), nullableTime(),
            ),
        ).thenReturn(listOf(trusted))
        `when`(
            actions.appendDictation(
                anyDoctor(),
                anyText(),
                nullableLong(),
                anyStrings(),
                org.mockito.ArgumentMatchers.anyBoolean(),
            ),
        ).thenReturn(mapOf("appointmentId" to 1217L, "saved" to true, "spokenHint" to "I added that to the consultation note."))

        val text =
            "Franko Xerselman bilan bo'ladigan 10:00 dagi qabulga quyidagi qaydlarni yozib bor: Franko Xerselman yuragida shikoyat bilan yonimga keldi."
        val result = tools.execute(
            doctor = doctor,
            toolName = "append_consultation_draft",
            args = emptyMap(),
            conversationId = "chat_schedule",
            latestUserText = text,
        )

        assertEquals(1217L, result["appointmentId"])
        assertEquals(true, result["saved"])
        verify(slots, org.mockito.Mockito.never()).availableSlotsForDay(
            anyDoctor(), anyDate(), nullableLong(), nullableLong(),
        )
        verifyNoInteractions(bookingWorkflows)
    }

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
        verifyNoInteractions(bookingWorkflows)
    }

    @Test
    fun `free slots use doctor timezone and exclude slots already in the past`() {
        val today = LocalDate.parse("2026-10-20")
        `when`(slots.availableSlotsForDay(doctor, today, null, null)).thenReturn(
            listOf(
                PatientDaySlotsService.AvailableSlotDto(
                    startAt = "2026-10-20T03:00:00Z",
                    endAt = "2026-10-20T03:30:00Z",
                    slotMinutes = 30,
                ),
                PatientDaySlotsService.AvailableSlotDto(
                    startAt = "2026-10-20T05:00:00Z",
                    endAt = "2026-10-20T05:30:00Z",
                    slotMinutes = 30,
                ),
            ),
        )

        val result = tools.execute(doctor, "list_free_slots", mapOf("date" to "today"))
        val available = result["slots"] as List<*>

        assertEquals(1, available.size)
        assertEquals("10:00", (available.single() as Map<*, *>)["startLocal"])
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
        val today = LocalDate.now(fixedClock.withZone(zone)) // the service uses fixedClock; the wall clock made this flaky
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

    private fun nullableText(): String? =
        org.mockito.ArgumentMatchers.nullable(String::class.java)

    private fun nullableLong(): Long? =
        org.mockito.ArgumentMatchers.nullable(Long::class.java)

    private fun nullableDate(): LocalDate? =
        org.mockito.ArgumentMatchers.nullable(LocalDate::class.java)

    private fun nullableTime(): java.time.LocalTime? =
        org.mockito.ArgumentMatchers.nullable(java.time.LocalTime::class.java)

    private fun anyIds(): Set<Long> =
        org.mockito.ArgumentMatchers.any<Set<Long>>() ?: emptySet()

    private fun pendingPatientWorkflow() = IntelligenceBookingWorkflow(
        id = UUID.randomUUID(),
        doctorId = 5,
        conversationId = "chat_patient",
        status = BookingWorkflowStatus.COLLECTING_DETAILS,
        patientId = null,
        patientName = "Franko Hersonman",
        appointmentDate = null,
        appointmentTime = null,
        proposalJson = null,
        confirmationToken = null,
        idempotencyKey = UUID.randomUUID(),
        appointmentId = null,
        pendingQuestionType = PendingQuestionType.CONFIRM_PATIENT,
        pendingQuestionJson = "[]",
        pendingQuestionToken = UUID.randomUUID(),
        version = 1,
        expiresAt = Instant.parse("2026-10-20T11:00:00Z"),
    )

    private fun trustedReference(id: Long, time: String) = TrustedAppointmentReference(
        appointmentId = id,
        patientId = 30L,
        patientName = "Franco Herselman",
        localDate = LocalDate.parse("2026-10-20"),
        localTime = java.time.LocalTime.parse(time),
        startAt = LocalDate.parse("2026-10-20").atTime(java.time.LocalTime.parse(time))
            .atZone(java.time.ZoneId.of("Asia/Tashkent")).toInstant(),
        status = "CONFIRMED",
    )
}
