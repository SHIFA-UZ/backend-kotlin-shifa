package com.shifa.doctors.api

import com.shifa.auth.domain.User
import com.shifa.doctors.application.ContractPdf
import com.shifa.doctors.application.DoctorContractSummary
import com.shifa.doctors.application.EarlyPartnerContractService
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.shared.CodedStatusException
import com.shifa.shared.ErrorCode
import com.shifa.shared.security.DoctorPrincipal
import java.time.LocalDate
import java.time.OffsetDateTime
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
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

/** BE-16: the doctor's own contract and its PDF. */
@WebMvcTest(
    controllers = [DoctorContractController::class],
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
class DoctorContractControllerTest {

    @Autowired lateinit var mockMvc: MockMvc

    @MockitoBean lateinit var contracts: EarlyPartnerContractService

    private val doctor = DoctorProfile(id = 9, user = User(id = 2, passwordHash = "x"), firstName = "Aziza", lastName = "Karimova")
    private val principal = DoctorPrincipal(doctor, listOf(SimpleGrantedAuthority("ROLE_DOCTOR")))

    @BeforeEach
    fun signIn() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
    }

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    private fun summary() = contracts.summaryForDoctor(eq(9L), any(LocalDate::class.java) ?: LocalDate.EPOCH)

    @Test
    fun `the contract summary`() {
        `when`(summary()).thenReturn(
            DoctorContractSummary(
                "SHIFA-0461", LocalDate.parse("2026-06-01"), LocalDate.parse("2026-12-01"), 6,
                OffsetDateTime.parse("2026-05-25T10:00:00+05:00"), true,
            ),
        )

        mockMvc.perform(get("/api/doctors/me/contract"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contractNumber").value("SHIFA-0461"))
            .andExpect(jsonPath("$.effectiveDate").value("2026-06-01"))
            .andExpect(jsonPath("$.endDate").value("2026-12-01"))
            .andExpect(jsonPath("$.termMonths").value(6))
            .andExpect(jsonPath("$.issuedAt").value("2026-05-25T05:00:00Z"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
    }

    @Test
    fun `no contract is 404 NO_CONTRACT for both`() {
        val none = CodedStatusException(HttpStatus.NOT_FOUND, "No early-partner contract", ErrorCode.NO_CONTRACT)
        `when`(summary()).thenThrow(none)
        `when`(contracts.pdfForDoctor(anyLong())).thenThrow(none)

        mockMvc.perform(get("/api/doctors/me/contract")).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("NO_CONTRACT"))
        mockMvc.perform(get("/api/doctors/me/contract/pdf")).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("NO_CONTRACT"))
    }

    @Test
    fun `the pdf is inline and named after the contract`() {
        `when`(contracts.pdfForDoctor(9L)).thenReturn(ContractPdf("SHIFA-0461", "%PDF-1.4".toByteArray()))

        mockMvc.perform(get("/api/doctors/me/contract/pdf"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition", "inline; filename=\"SHIFA-0461.pdf\""))
    }
}
