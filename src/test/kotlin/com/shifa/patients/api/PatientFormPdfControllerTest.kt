package com.shifa.patients.api

import com.shifa.auth.domain.User
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.documents.application.FiledDocument
import com.shifa.patients.application.PatientFormService
import com.shifa.shared.security.DoctorPrincipal
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
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException

/** `GET …/forms/{formId}/pdf` and `POST …/forms/{formId}/pdf-document` (BE-01). */
@WebMvcTest(
    controllers = [PatientFormPdfController::class],
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
class PatientFormPdfControllerTest {

    @Autowired lateinit var mockMvc: MockMvc

    @MockitoBean lateinit var formService: PatientFormService
    @MockitoBean lateinit var clinicAccess: com.shifa.clinics.application.ClinicAccessService

    private val doctor = DoctorProfile(id = 9, user = User(id = 2, passwordHash = "x"), firstName = "A", lastName = "B")
    private val pdf = "%PDF-1.7 test".toByteArray()

    @BeforeEach
    fun signIn() {
        val principal = DoctorPrincipal(doctor, listOf(SimpleGrantedAuthority("ROLE_DOCTOR")))
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
    }

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    @Test
    fun `the pdf is served inline with the form number in its name`() {
        `when`(formService.renderPdf(77L, 5L)).thenReturn(PatientFormService.FormPdf("025-2-12.pdf", pdf))

        mockMvc.perform(get("/api/patients/77/forms/5/pdf"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition", "inline; filename=\"025-2-12.pdf\""))
            .andExpect(content().bytes(pdf))
    }

    @Test
    fun `a form of another patient is a bad request, as the form read`() {
        `when`(formService.renderPdf(78L, 5L)).thenThrow(IllegalArgumentException("Form does not belong to patient 78"))

        mockMvc.perform(get("/api/patients/78/forms/5/pdf")).andExpect(status().isBadRequest)
    }

    @Test
    fun `an unknown form is not found`() {
        `when`(formService.renderPdf(77L, 6L)).thenThrow(ResponseStatusException(HttpStatus.NOT_FOUND, "Form not found: 6"))

        mockMvc.perform(get("/api/patients/77/forms/6/pdf")).andExpect(status().isNotFound)
    }

    @Test
    fun `a patient outside the doctor's directory is not found, nothing rendered or filed`() {
        `when`(clinicAccess.assertPatientVisible(org.mockito.ArgumentMatchers.any() ?: Any(), org.mockito.ArgumentMatchers.eq(90L)))
            .thenThrow(ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found"))

        mockMvc.perform(get("/api/patients/90/forms/5/pdf")).andExpect(status().isNotFound)
        mockMvc.perform(post("/api/patients/90/forms/5/pdf-document")).andExpect(status().isNotFound)
        org.mockito.Mockito.verifyNoInteractions(formService)
    }

    @Test
    fun `filing answers the document id and url, as the signed-in doctor`() {
        `when`(formService.filePdfDocument(77L, 5L, doctor)).thenReturn(FiledDocument(55L, "https://x/patientdocuments/77/f.pdf?sig=s"))

        mockMvc.perform(post("/api/patients/77/forms/5/pdf-document"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.documentId").value(55))
            .andExpect(jsonPath("$.url").value("https://x/patientdocuments/77/f.pdf?sig=s"))
    }

    @Test
    fun `filing a form of another patient is a bad request`() {
        `when`(formService.filePdfDocument(78L, 5L, doctor)).thenThrow(IllegalArgumentException("Form does not belong to patient 78"))

        mockMvc.perform(post("/api/patients/78/forms/5/pdf-document")).andExpect(status().isBadRequest)
    }
}
