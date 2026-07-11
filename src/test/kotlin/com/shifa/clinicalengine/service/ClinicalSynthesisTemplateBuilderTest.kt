package com.shifa.clinicalengine.service

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClinicalSynthesisTemplateBuilderTest {

    @Test
    fun `bone destruction xray template produces professional sentence`() {
        val template = ClinicalSynthesisTemplateBuilder.build(
            field = "xray",
            label = "Очаг деструкции кости [X] мм",
            locale = "ru",
            variables = listOf("X"),
        )
        val sentence = ClinicalSynthesisTemplateBuilder.renderTemplate(template, mapOf("X" to "5"))
        assertTrue(sentence.contains("5"))
        assertTrue(sentence.contains("деструкц"))
        assertTrue(!sentence.startsWith("Очаг"))
    }

    @Test
    fun `asymptomatic complaints use negative phrasing`() {
        val template = ClinicalSynthesisTemplateBuilder.build(
            field = "complaints",
            label = "Отсутствуют",
            locale = "ru",
            variables = emptyList(),
        )
        val sentence = ClinicalSynthesisTemplateBuilder.renderTemplate(template, emptyMap())
        assertTrue(sentence.contains("не предъявляет"))
    }
}
