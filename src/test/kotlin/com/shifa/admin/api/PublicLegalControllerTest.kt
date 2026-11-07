package com.shifa.admin.api

import com.shifa.admin.application.LegalDocuments
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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

/** BE-16: the public legal links. */
@WebMvcTest(
    controllers = [PublicLegalController::class],
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
class PublicLegalControllerTest {

    @Autowired lateinit var mockMvc: MockMvc

    @MockitoBean lateinit var legal: LegalDocuments

    @Test
    fun `the links in the asked language, English by default`() {
        `when`(legal.links("en")).thenReturn(LegalDocuments.Links("https://shifa.uz/#terms", "https://shifa.uz/#privacy", null, "1", "2026-11-07"))
        `when`(legal.links("uz")).thenReturn(LegalDocuments.Links("https://shifa.uz/uz#terms", "https://shifa.uz/#privacy", null, "1", "2026-11-07"))

        mockMvc.perform(get("/api/public/legal"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.terms").value("https://shifa.uz/#terms"))
            .andExpect(jsonPath("$.privacy").value("https://shifa.uz/#privacy"))
            .andExpect(jsonPath("$.licences").doesNotExist())
            .andExpect(jsonPath("$.version").value("1"))
            .andExpect(jsonPath("$.updatedAt").value("2026-11-07"))
        mockMvc.perform(get("/api/public/legal").param("lang", "uz")).andExpect(jsonPath("$.terms").value("https://shifa.uz/uz#terms"))
    }

    @Test
    fun `another language is a bad request`() {
        mockMvc.perform(get("/api/public/legal").param("lang", "fr")).andExpect(status().isBadRequest)
    }
}
