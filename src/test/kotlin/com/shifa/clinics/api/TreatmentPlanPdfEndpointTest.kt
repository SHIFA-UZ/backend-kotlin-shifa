package com.shifa.clinics.api

import com.shifa.auth.domain.User
import com.shifa.clinics.application.ClinicAccessService
import com.shifa.clinics.domain.Clinic
import com.shifa.clinics.domain.TreatmentPlan
import com.shifa.clinics.domain.TreatmentPlanLine
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.doctors.domain.DoctorSettings
import com.shifa.patients.domain.PatientProfile
import com.shifa.shared.security.DoctorPrincipal
import java.util.Optional
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException

/** `GET /api/treatment-plans/{planId}/pdf` (BE-01): the detail's access checks, the doctor's language by default. */
@WebMvcTest(
    controllers = [TreatmentPlanController::class],
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
class TreatmentPlanPdfEndpointTest {

    @Autowired lateinit var mockMvc: MockMvc

    @MockitoBean lateinit var clinicAccess: ClinicAccessService
    @MockitoBean lateinit var clinicCatalog: com.shifa.clinics.application.ClinicCatalogService
    @MockitoBean lateinit var financeService: com.shifa.clinics.application.TreatmentPlanFinanceService
    @MockitoBean lateinit var clinicFinanceLedger: com.shifa.clinics.application.ClinicFinanceLedgerService
    @MockitoBean lateinit var fulfillmentService: com.shifa.clinics.application.TreatmentPlanFulfillmentService
    @MockitoBean lateinit var clinics: com.shifa.clinics.application.Clinics
    @MockitoBean lateinit var patients: com.shifa.patients.application.PatientProfiles
    @MockitoBean lateinit var doctors: com.shifa.doctors.application.DoctorProfiles
    @MockitoBean lateinit var catalogRepo: com.shifa.clinics.application.TreatmentPlanCatalogItems
    @MockitoBean lateinit var plans: com.shifa.clinics.application.TreatmentPlans
    @MockitoBean lateinit var linesRepo: com.shifa.clinics.application.TreatmentPlanLines
    @MockitoBean lateinit var paymentsRepo: com.shifa.clinics.application.TreatmentPlanPayments
    @MockitoBean lateinit var appts: com.shifa.appointments.application.Appointments
    @MockitoBean lateinit var installmentPlans: com.shifa.clinics.application.InstallmentPlans
    @MockitoBean lateinit var installmentItems: com.shifa.clinics.application.InstallmentItems
    @MockitoBean lateinit var users: com.shifa.auth.application.Users
    @MockitoBean lateinit var notificationService: com.shifa.notifications.application.NotificationService
    @MockitoBean lateinit var doctorLocations: com.shifa.doctors.application.DoctorLocations
    @MockitoBean lateinit var patientDaySlots: com.shifa.appointments.application.PatientDaySlotsService
    @MockitoBean lateinit var doctorServices: com.shifa.doctors.application.DoctorServices
    @MockitoBean lateinit var doctorServicePrices: com.shifa.doctors.application.DoctorServicePrices
    @MockitoBean lateinit var doctorSettings: com.shifa.doctors.application.DoctorSettingsDoor
    @MockitoBean lateinit var financeAccess: com.shifa.clinics.application.ClinicFinanceAccessService
    @MockitoBean lateinit var installmentService: com.shifa.clinics.application.InstallmentService
    @MockitoBean lateinit var auditService: com.shifa.clinics.application.ClinicFinanceAuditService

    private val doctor = DoctorProfile(id = 9, user = User(id = 2, passwordHash = "x"), firstName = "Aziza", lastName = "Karimova")
    private val principal = DoctorPrincipal(doctor, listOf(SimpleGrantedAuthority("ROLE_DOCTOR")))
    private val plan = TreatmentPlan(
        id = 42L,
        clinic = Clinic(id = 3L, name = "Shifa Demo Clinic"),
        patient = PatientProfile(id = 77L, fullName = "Иванов Иван"),
        status = TreatmentPlan.Status.ACTIVE,
        title = "Санация",
    )

    @BeforeEach
    fun signIn() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        `when`(plans.findById(42L)).thenReturn(Optional.of(plan))
        `when`(linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(42L)).thenReturn(
            listOf(
                TreatmentPlanLine(id = 1, plan = plan, title = "Пломба", unitPriceMinor = 150_000_00, specialtyMetadata = """{"fdi":"16"}"""),
                TreatmentPlanLine(id = 2, plan = plan, title = "Консультация", unitPriceMinor = 50_000_00, sortOrder = 1),
            ),
        )
    }

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    private fun pdfText(bytes: ByteArray) = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }

    @Test
    fun `the plan pdf is served inline in the requested language`() {
        val bytes = mockMvc.perform(get("/api/treatment-plans/42/pdf").param("lang", "ru"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition", "inline; filename=\"treatment-plan-42.pdf\""))
            .andReturn().response.contentAsByteArray
        val text = pdfText(bytes)
        listOf("План лечения · Санация", "ID плана: #42", "Иванов Иван", "Зуб 16 (FDI)", "Другие услуги", "Shifa Demo Clinic")
            .forEach { assertTrue(text.contains(it), "missing \"$it\" in:\n$text") }
    }

    @Test
    fun `without lang the signed-in doctor's language is used`() {
        `when`(doctorSettings.findByDoctorId(9L)).thenReturn(Optional.of(DoctorSettings(doctor = doctor, language = "uz")))

        val bytes = mockMvc.perform(get("/api/treatment-plans/42/pdf")).andExpect(status().isOk).andReturn().response.contentAsByteArray
        assertTrue(pdfText(bytes).contains("Davolash rejasi"))
    }

    @Test
    fun `a caller outside the clinic gets the detail's 403`() {
        doThrow(ResponseStatusException(HttpStatus.FORBIDDEN)).`when`(clinicAccess).assertPrincipalMayAccessClinic(principal, 3L)

        mockMvc.perform(get("/api/treatment-plans/42/pdf")).andExpect(status().isForbidden)
    }

    @Test
    fun `a patient the caller can't see is not found, as in the detail`() {
        doThrow(ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")).`when`(clinicAccess).assertPatientVisible(principal, 77L)

        mockMvc.perform(get("/api/treatment-plans/42/pdf")).andExpect(status().isNotFound)
    }

    @Test
    fun `an unknown plan is not found`() {
        `when`(plans.findById(43L)).thenReturn(Optional.empty())

        mockMvc.perform(get("/api/treatment-plans/43/pdf")).andExpect(status().isNotFound)
    }

    @Test
    fun `an unsupported language is a bad request`() {
        mockMvc.perform(get("/api/treatment-plans/42/pdf").param("lang", "fr")).andExpect(status().isBadRequest)
    }
}
