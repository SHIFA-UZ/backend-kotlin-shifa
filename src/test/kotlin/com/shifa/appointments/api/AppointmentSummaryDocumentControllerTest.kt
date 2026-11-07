package com.shifa.appointments.api

import com.shifa.appointments.application.AppointmentDocumentationService
import com.shifa.appointments.application.Appointments
import com.shifa.appointments.domain.Appointment
import com.shifa.auth.domain.User
import com.shifa.clinics.application.ClinicAccessService
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.documents.application.FiledDocument
import com.shifa.documents.application.PatientDocumentStorageService
import com.shifa.documents.domain.PatientDocument
import com.shifa.patients.domain.PatientProfile
import com.shifa.shared.security.DoctorPrincipal
import java.time.Instant
import java.util.Optional
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException

/** `POST /api/appointments/{id}/summary-document` (BE-01): access as the appointment read, 409 without notes. */
@WebMvcTest(
    controllers = [AppointmentSummaryDocumentController::class],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [
                com.shifa.shared.security.AiRateLimitFilter::class,
                com.shifa.shared.security.JwtAuthFilter::class,
                com.shifa.shared.security.UserRateLimitFilter::class,
            ],
        ),
    ],
)
@AutoConfigureMockMvc(addFilters = false)
@Import(com.shifa.shared.config.CacheConfig::class, com.shifa.shared.security.AuthenticationPrincipalTestConfig::class)
class AppointmentSummaryDocumentControllerTest {

    @Autowired lateinit var mockMvc: MockMvc

    @MockitoBean lateinit var appts: Appointments
    @MockitoBean lateinit var clinicAccess: ClinicAccessService
    @MockitoBean lateinit var documentation: AppointmentDocumentationService
    @MockitoBean lateinit var storage: PatientDocumentStorageService

    private val doctor = DoctorProfile(id = 9, user = User(id = 2, passwordHash = "x"), firstName = "A", lastName = "B")
    private val principal = DoctorPrincipal(doctor, listOf(SimpleGrantedAuthority("ROLE_DOCTOR")))
    private val appointment = Appointment(
        id = 10L,
        doctor = doctor,
        patient = PatientProfile(id = 77L, fullName = "Jane Doe"),
        startAt = Instant.parse("2026-11-06T09:00:00Z"),
        endAt = Instant.parse("2026-11-06T09:30:00Z"),
        location = "Clinic",
    )
    private val document = PatientDocument(id = 55L, filePath = "patientdocuments/77/a.pdf")

    @BeforeEach
    fun signIn() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        `when`(appts.findById(10L)).thenReturn(Optional.of(appointment))
        `when`(storage.filed(document)).thenReturn(FiledDocument(55L, "https://api.shifa.uz/patientdocuments/77/a.pdf?exp=1&sig=s"))
    }

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    @Test
    fun `files the summary and answers its id and signed url`() {
        `when`(documentation.generateForAppointment(10L, "ru")).thenReturn(document)

        mockMvc.perform(post("/api/appointments/10/summary-document").param("lang", "RU"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.documentId").value(55))
            .andExpect(jsonPath("$.url").value("https://api.shifa.uz/patientdocuments/77/a.pdf?exp=1&sig=s"))
        verify(clinicAccess).assertCanAccessAppointmentResource(principal, 9L)
    }

    @Test
    fun `without lang the service picks the default language`() {
        `when`(documentation.generateForAppointment(10L, null)).thenReturn(document)

        mockMvc.perform(post("/api/appointments/10/summary-document")).andExpect(status().isOk)
    }

    @Test
    fun `a visit without notes is a conflict`() {
        `when`(documentation.generateForAppointment(10L, null)).thenReturn(null)

        mockMvc.perform(post("/api/appointments/10/summary-document"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.message").value("The visit has no notes yet, so there is no summary to file"))
    }

    @Test
    fun `an unknown appointment is not found`() {
        `when`(appts.findById(11L)).thenReturn(Optional.empty())

        mockMvc.perform(post("/api/appointments/11/summary-document")).andExpect(status().isNotFound)
    }

    @Test
    fun `a caller without access gets the read endpoint's 403 and nothing is filed`() {
        doThrow(ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot access this doctor's calendar"))
            .`when`(clinicAccess).assertCanAccessAppointmentResource(principal, 9L)

        mockMvc.perform(post("/api/appointments/10/summary-document")).andExpect(status().isForbidden)
        verify(documentation, never()).generateForAppointment(ArgumentMatchers.anyLong(), ArgumentMatchers.any())
    }

    @Test
    fun `a colleague who can see the calendar gets 403 and nothing is filed`() {
        val colleague = DoctorProfile(id = 12, user = User(id = 3, passwordHash = "x"), firstName = "C", lastName = "D")
        val other = DoctorPrincipal(colleague, listOf(SimpleGrantedAuthority("ROLE_DOCTOR")))
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(other, null, other.authorities)

        mockMvc.perform(post("/api/appointments/10/summary-document")).andExpect(status().isForbidden)
        verify(documentation, never()).generateForAppointment(ArgumentMatchers.anyLong(), ArgumentMatchers.any())
    }

    @Test
    fun `an unsupported language is a bad request`() {
        mockMvc.perform(post("/api/appointments/10/summary-document").param("lang", "de"))
            .andExpect(status().isBadRequest)
    }
}
