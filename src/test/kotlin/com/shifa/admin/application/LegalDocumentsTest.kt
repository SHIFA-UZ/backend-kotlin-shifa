package com.shifa.admin.application

import com.shifa.admin.domain.SystemConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class LegalDocumentsTest {
    private val configs = mock(SystemConfigs::class.java)
    private val legal = LegalDocuments(configs)

    init {
        `when`(configs.findAll()).thenReturn(
            listOf(
                "legal.terms_url" to "https://shifa.uz/#terms",
                "legal.terms_url.ru" to " https://shifa.uz/ru#terms ",
                "legal.terms_url.uz" to "",
                "legal.privacy_url" to "https://shifa.uz/#privacy",
                "legal.licences_url" to "",
                "legal.version" to "1",
                "legal.updated_at" to "2026-11-07",
                "doctor_app.maintenance.active" to "false",
            ).map { (k, v) -> SystemConfig(key = k, value = v) },
        )
    }

    @Test
    fun `a language override wins and an empty one falls back to the default`() {
        assertEquals(LegalDocuments.Links("https://shifa.uz/ru#terms", "https://shifa.uz/#privacy", null, "1", "2026-11-07"), legal.links("ru"))
        assertEquals("https://shifa.uz/#terms", legal.links("uz").terms)
        assertEquals("https://shifa.uz/#terms", legal.links("en").terms)
    }
}
