package com.shifa.shared.api

import com.shifa.shared.storage.FileStorage
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.core.io.ByteArrayResource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    controllers = [StoredFileController::class],
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
@Import(com.shifa.shared.config.CacheConfig::class) // Boot 4's @WebMvcTest slice no longer brings a CacheManager for @EnableCaching
class StoredFileControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var storage: FileStorage

    @Test
    fun `serves a patient document inline with its type and cache header`() {
        `when`(storage.open("patientdocuments/7/report.pdf")).thenReturn(ByteArrayResource("%PDF".toByteArray()))

        mockMvc.perform(get("/patientdocuments/7/report.pdf"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andExpect(header().string("Content-Disposition", "inline; filename=\"report.pdf\""))
            .andExpect(header().string("Cache-Control", "max-age=3600, public"))
            .andExpect(content().bytes("%PDF".toByteArray()))
    }

    @Test
    fun `a photo is served without a disposition, a missing file is 404`() {
        `when`(storage.open("doctors/jane-7.jpg")).thenReturn(ByteArrayResource(byteArrayOf(1, 2)))

        mockMvc.perform(get("/doctors/jane-7.jpg"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "image/jpeg"))
            .andExpect(header().doesNotExist("Content-Disposition"))
        mockMvc.perform(get("/doctors/missing.jpg")).andExpect(status().isNotFound)
    }
}
