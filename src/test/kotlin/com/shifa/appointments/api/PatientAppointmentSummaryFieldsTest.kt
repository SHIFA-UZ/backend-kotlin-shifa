package com.shifa.appointments.api

import com.shifa.appointments.domain.Appointment
import com.shifa.auth.domain.User
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.patients.domain.PatientProfile
import com.shifa.shared.security.PatientPrincipal
import java.time.Duration
import java.time.Instant
import java.util.Optional
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** The patient's appointment detail carries the cancel reason and the video join window (BE-10, BE-28). */
@WebMvcTest(
    controllers = [PatientAppointmentController::class],
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
class PatientAppointmentSummaryFieldsTest {

    @Autowired lateinit var mockMvc: MockMvc

    @MockitoBean lateinit var appts: com.shifa.appointments.application.Appointments
    @MockitoBean lateinit var profileMapper: com.shifa.patients.application.PatientProfileMapper
    @MockitoBean lateinit var reviews: com.shifa.doctors.application.DoctorReviews
    @MockitoBean lateinit var visitSummaries: com.shifa.ai.application.PatientVisitAiSummaryService
    @MockitoBean lateinit var documents: com.shifa.documents.application.PatientDocumentService
    @MockitoBean lateinit var currentPatient: com.shifa.patients.application.CurrentPatientResolver

    private val user = User(id = 3, passwordHash = "x")
    private val principal = PatientPrincipal(user, listOf(SimpleGrantedAuthority("ROLE_PATIENT")))
    private val patient = PatientProfile(id = 7L, fullName = "Franco")
    private val doctor = DoctorProfile(id = 9, user = User(id = 2, passwordHash = "x"), firstName = "Aziza", lastName = "Karimova")
    private val start = Instant.parse("2099-11-07T09:00:00Z")

    private fun visit(video: Boolean) = Appointment(
        id = 42, doctor = doctor, patient = patient, startAt = start, endAt = start.plus(Duration.ofMinutes(30)),
        location = "Video Consultation", isVideoConsultation = video, status = Appointment.Status.CANCELLED,
    )

    @BeforeEach
    fun signIn() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        `when`(currentPatient.resolve(principal)).thenReturn(patient)
    }

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    @Test
    fun `the detail has the cancel reason, the note and the join window`() {
        val v = visit(video = true).apply {
            cancelReasonCode = Appointment.CancelReason.DOCTOR_UNAVAILABLE
            cancelNote = "Sick today"
        }
        `when`(appts.findByIdWithDoctorAndPatient(42L)).thenReturn(Optional.of(v))

        mockMvc.perform(get("/api/patients/me/appointments/42"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.cancelReasonCode").value("DOCTOR_UNAVAILABLE"))
            .andExpect(jsonPath("$.cancelNote").value("Sick today"))
            .andExpect(jsonPath("$.joinWindow.from").value("2099-11-07T08:55:00Z"))
            .andExpect(jsonPath("$.joinWindow.to").value("2099-11-07T09:45:00Z"))
    }

    @Test
    fun `an in-person visit without a reason has nulls`() {
        `when`(appts.findByIdWithDoctorAndPatient(42L)).thenReturn(Optional.of(visit(video = false)))

        mockMvc.perform(get("/api/patients/me/appointments/42"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.cancelReasonCode").value(nullValue()))
            .andExpect(jsonPath("$.cancelNote").value(nullValue()))
            .andExpect(jsonPath("$.joinWindow").value(nullValue()))
    }
}
