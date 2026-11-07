package com.shifa.appointments.api

import com.shifa.appointments.domain.Appointment
import com.shifa.auth.domain.User
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.patients.domain.PatientProfile
import com.shifa.shared.security.DoctorPrincipal
import java.time.Duration
import java.time.Instant
import java.util.Optional
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** BE-28: the video token's refusals keep their statuses and gain codes; the window ones also carry the window. */
@WebMvcTest(
    controllers = [VideoController::class],
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
class VideoTokenErrorsTest {

    @Autowired lateinit var mockMvc: MockMvc

    @MockitoBean lateinit var daily: com.shifa.appointments.infrastructure.DailyVideoService
    @MockitoBean lateinit var appointments: com.shifa.appointments.application.Appointments
    @MockitoBean lateinit var tiers: com.shifa.payments.application.SubscriptionTierService
    @MockitoBean lateinit var currentPatient: com.shifa.patients.application.CurrentPatientResolver
    @MockitoBean lateinit var presence: com.shifa.appointments.application.VideoPresenceService

    private val doctor = DoctorProfile(id = 9, user = User(id = 2, passwordHash = "x"), firstName = "Aziza", lastName = "Karimova")
    private val principal = DoctorPrincipal(doctor, listOf(SimpleGrantedAuthority("ROLE_DOCTOR")))

    private fun visitAt(start: Instant, amountMinor: Long? = null) = Appointment(
        id = 42, doctor = doctor, patient = PatientProfile(id = 7L, fullName = "Franco"),
        startAt = start, endAt = start.plus(Duration.ofMinutes(30)), location = "Video Consultation",
        isVideoConsultation = true, status = Appointment.Status.CONFIRMED, paymentAmountMinor = amountMinor,
        paymentStatus = if (amountMinor != null) Appointment.PaymentStatus.PENDING else Appointment.PaymentStatus.NOT_REQUIRED,
    )

    @BeforeEach
    fun signIn() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        `when`(daily.isApiKeyConfigured()).thenReturn(true)
    }

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    private fun token() = mockMvc.perform(post("/api/video/token").contentType(MediaType.APPLICATION_JSON).content("""{"appointmentId":42}"""))

    @Test
    fun `too early is 403 VIDEO_TOO_EARLY with the window`() {
        val start = Instant.parse("2099-01-01T09:00:00Z")
        `when`(appointments.findById(42L)).thenReturn(Optional.of(visitAt(start)))

        token().andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("VIDEO_TOO_EARLY"))
            .andExpect(jsonPath("$.message").value("Video call is not yet available. You can join 5 minutes before the appointment start."))
            .andExpect(jsonPath("$.error").value(403))
            .andExpect(jsonPath("$.joinWindow.from").value("2099-01-01T08:55:00Z"))
            .andExpect(jsonPath("$.joinWindow.to").value("2099-01-01T09:45:00Z"))
    }

    @Test
    fun `too late is 403 VIDEO_TOO_LATE with the window`() {
        `when`(appointments.findById(42L)).thenReturn(Optional.of(visitAt(Instant.parse("2020-01-01T09:00:00Z"))))

        token().andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("VIDEO_TOO_LATE"))
            .andExpect(jsonPath("$.joinWindow.to").value("2020-01-01T09:45:00Z"))
    }

    @Test
    fun `unpaid is 402 VIDEO_PAYMENT_REQUIRED without a window`() {
        `when`(appointments.findById(42L)).thenReturn(Optional.of(visitAt(Instant.now(), amountMinor = 100_000)))

        token().andExpect(status().isPaymentRequired)
            .andExpect(jsonPath("$.code").value("VIDEO_PAYMENT_REQUIRED"))
            .andExpect(jsonPath("$.joinWindow").doesNotExist())
    }

    @Test
    fun `someone else's visit is refused before its times are told`() {
        val other = DoctorProfile(id = 12, user = User(id = 3, passwordHash = "x"), firstName = "B", lastName = "C")
        val visit = visitAt(Instant.parse("2099-01-01T09:00:00Z"))
        `when`(appointments.findById(42L)).thenReturn(Optional.of(Appointment(
            id = 42, doctor = other, patient = visit.patient, startAt = visit.startAt, endAt = visit.endAt,
            location = visit.location, isVideoConsultation = true, status = Appointment.Status.CONFIRMED,
        )))

        token().andExpect(status().isForbidden)
            .andExpect(jsonPath("$.joinWindow").doesNotExist())
            .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not("VIDEO_TOO_EARLY")))
    }
}
