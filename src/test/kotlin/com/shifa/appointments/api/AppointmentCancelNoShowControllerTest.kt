package com.shifa.appointments.api

import com.shifa.appointments.application.AppointmentCancellationService
import com.shifa.appointments.domain.Appointment
import com.shifa.auth.domain.User
import com.shifa.clinics.application.ClinicAccessService
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.patients.domain.PatientProfile
import com.shifa.shared.CodedStatusException
import com.shifa.shared.ErrorCode
import com.shifa.shared.security.DoctorPrincipal
import java.time.Duration
import java.time.Instant
import java.util.Optional
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.eq
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
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException

/** BE-09/10/28 on the doctor appointment: the new fields, cancel with a reason, and no-show. */
@WebMvcTest(
    controllers = [AppointmentController::class],
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
class AppointmentCancelNoShowControllerTest {

    @Autowired lateinit var mockMvc: MockMvc

    @MockitoBean lateinit var appts: com.shifa.appointments.application.Appointments
    @MockitoBean lateinit var notificationService: com.shifa.notifications.application.NotificationService
    @MockitoBean lateinit var consultationNotes: com.shifa.appointments.application.ConsultationNotes
    @MockitoBean lateinit var aiDraftNotes: com.shifa.ai.application.AiDraftNotes
    @MockitoBean lateinit var ragIndexing: com.shifa.ai.application.ClinicalRagIndexingService
    @MockitoBean lateinit var clinicAccess: ClinicAccessService
    @MockitoBean lateinit var visitCharges: com.shifa.clinics.application.VisitChargeService
    @MockitoBean lateinit var fulfillment: com.shifa.clinics.application.TreatmentPlanFulfillmentService
    @MockitoBean lateinit var planLinks: com.shifa.clinics.application.TreatmentPlanAppointmentLinks
    @MockitoBean lateinit var lineFulfillments: com.shifa.clinics.application.TreatmentPlanLineFulfillments
    @MockitoBean lateinit var slots: com.shifa.appointments.application.SlotAvailabilityService
    @MockitoBean lateinit var cancellation: AppointmentCancellationService
    @MockitoBean lateinit var lifecycle: com.shifa.appointments.application.AppointmentVisitLifecycleService
    @MockitoBean lateinit var briefings: com.shifa.ai.application.AppointmentBriefingService
    @MockitoBean lateinit var scribe: com.shifa.ai.application.ScribePipelineService
    @MockitoBean lateinit var documentation: com.shifa.appointments.application.AppointmentDocumentationService
    @MockitoBean lateinit var reschedule: com.shifa.appointments.application.DoctorAppointmentRescheduleService
    @MockitoBean lateinit var documents: com.shifa.documents.application.PatientDocumentService

    private val doctor = DoctorProfile(id = 9, user = User(id = 2, passwordHash = "x"), firstName = "Aziza", lastName = "Karimova")
    private val principal = DoctorPrincipal(doctor, listOf(SimpleGrantedAuthority("ROLE_DOCTOR")))
    private val start = Instant.parse("2099-11-07T09:00:00Z")

    private fun visit(video: Boolean = true) = Appointment(
        id = 42,
        doctor = doctor,
        patient = PatientProfile(id = 7L, fullName = "Franco"),
        startAt = start,
        endAt = start.plus(Duration.ofMinutes(30)),
        location = "Video Consultation",
        isVideoConsultation = video,
        status = Appointment.Status.CONFIRMED,
    )

    /** markNoShow(v, notify, any time): the controller passes the current instant. */
    private fun noShow(v: Appointment, notify: Boolean) =
        cancellation.markNoShow(eq(v) ?: v, eq(notify), any(Instant::class.java) ?: Instant.EPOCH)

    @BeforeEach
    fun signIn() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
    }

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    @Test
    fun `the appointment carries the video flag, the join window and the cancel reason`() {
        val v = visit().apply {
            cancelReasonCode = Appointment.CancelReason.RESCHEDULED
            cancelNote = "Moving to Friday"
        }
        `when`(appts.findById(42L)).thenReturn(Optional.of(v))

        mockMvc.perform(get("/api/appointments/42"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isVideoConsultation").value(true))
            .andExpect(jsonPath("$.joinWindow.from").value("2099-11-07T08:55:00Z"))
            .andExpect(jsonPath("$.joinWindow.to").value("2099-11-07T09:45:00Z"))
            .andExpect(jsonPath("$.cancelReasonCode").value("RESCHEDULED"))
            .andExpect(jsonPath("$.cancelNote").value("Moving to Friday"))
    }

    @Test
    fun `a visit in person has no join window`() {
        `when`(appts.findById(42L)).thenReturn(Optional.of(visit(video = false)))

        mockMvc.perform(get("/api/appointments/42"))
            .andExpect(jsonPath("$.isVideoConsultation").value(false))
            .andExpect(jsonPath("$.joinWindow").value(nullValue()))
    }

    @Test
    fun `cancel with a reason uses the cancellation service`() {
        val v = visit()
        `when`(appts.findById(42L)).thenReturn(Optional.of(v))
        `when`(cancellation.cancelByDoctorManual(v, Appointment.CancelReason.DOCTOR_UNAVAILABLE, "Sick")).thenReturn(true)

        mockMvc.perform(
            post("/api/appointments/42/cancel").contentType(MediaType.APPLICATION_JSON)
                .content("""{"reasonCode":"DOCTOR_UNAVAILABLE","note":"Sick"}"""),
        ).andExpect(status().isOk)

        verify(cancellation).cancelByDoctorManual(v, Appointment.CancelReason.DOCTOR_UNAVAILABLE, "Sick")
    }

    @Test
    fun `an unknown or missing reason or a long note is a validation error`() {
        listOf("""{"reasonCode":"BORED"}""", """{}""", """{"reasonCode":"OTHER","note":"${"x".repeat(501)}"}""").forEach { body ->
            mockMvc.perform(post("/api/appointments/42/cancel").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("VALIDATION"))
        }
    }

    @Test
    fun `a past visit can't be cancelled with a reason either`() {
        `when`(appts.findById(42L)).thenReturn(Optional.of(visit().apply { startAt = Instant.parse("2020-01-01T09:00:00Z") }))

        mockMvc.perform(post("/api/appointments/42/cancel").contentType(MediaType.APPLICATION_JSON).content("""{"reasonCode":"OTHER"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("Cannot cancel a past appointment"))
    }

    @Test
    fun `staff outside the clinic get the access check's 403`() {
        `when`(appts.findById(42L)).thenReturn(Optional.of(visit()))
        doThrow(ResponseStatusException(HttpStatus.FORBIDDEN)).`when`(clinicAccess).assertCanAccessAppointmentResource(principal, 9L)

        mockMvc.perform(post("/api/appointments/42/no-show")).andExpect(status().isForbidden)
        verify(cancellation, never()).markNoShow(any(Appointment::class.java) ?: visit(), anyBoolean(), any(Instant::class.java) ?: Instant.EPOCH)
    }

    @Test
    fun `no-show without a body notifies and returns the appointment`() {
        val v = visit()
        `when`(appts.findById(42L)).thenReturn(Optional.of(v))
        `when`(noShow(v, true)).thenAnswer { v.apply { status = Appointment.Status.NO_SHOW } }

        mockMvc.perform(post("/api/appointments/42/no-show"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(42))
            .andExpect(jsonPath("$.status").value("NO_SHOW"))
        verify(cancellation).markNoShow(eq(v) ?: v, eq(true), any(Instant::class.java) ?: Instant.EPOCH)
    }

    @Test
    fun `no-show with notify false does not notify`() {
        val v = visit()
        `when`(appts.findById(42L)).thenReturn(Optional.of(v))
        `when`(noShow(v, false)).thenAnswer { v.apply { status = Appointment.Status.NO_SHOW } }

        mockMvc.perform(post("/api/appointments/42/no-show").contentType(MediaType.APPLICATION_JSON).content("""{"notify":false}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("NO_SHOW"))
        verify(cancellation).markNoShow(eq(v) ?: v, eq(false), any(Instant::class.java) ?: Instant.EPOCH)
    }

    @Test
    fun `a refused no-show is 409 with its code`() {
        val v = visit()
        `when`(appts.findById(42L)).thenReturn(Optional.of(v))
        `when`(noShow(v, true)).thenThrow(
            CodedStatusException(HttpStatus.CONFLICT, "nope", ErrorCode.NO_SHOW_NOT_ALLOWED),
        )

        mockMvc.perform(post("/api/appointments/42/no-show"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("NO_SHOW_NOT_ALLOWED"))
    }
}
