package com.shifa.ai.llm

import com.shifa.ai.OutputLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RouteLanguageAndCategoryTest {
    @Test
    fun `short inputs keep the requested language`() {
        assertEquals(OutputLanguage.EN, RouteLanguageDetector.detect("Botir Muzrabov", OutputLanguage.EN))
        assertEquals(OutputLanguage.RU, RouteLanguageDetector.detect("Botir", OutputLanguage.RU))
        assertEquals(OutputLanguage.UZ, RouteLanguageDetector.detect("ok", OutputLanguage.UZ))
    }

    @Test
    fun `uzbek latin and cyrillic are detected`() {
        assertEquals(OutputLanguage.UZ, RouteLanguageDetector.detect("Bugun qancha bemor qabul qilaman?", OutputLanguage.EN))
        assertEquals(OutputLanguage.UZ, RouteLanguageDetector.detect("Ertaga bo'sh vaqtlarim bormi", OutputLanguage.EN))
        assertEquals(OutputLanguage.UZ, RouteLanguageDetector.detect("24-oktabr soat 8:00 ga ko'chir", OutputLanguage.EN))
        assertEquals(OutputLanguage.UZ, RouteLanguageDetector.detect("Бугун қанча бемор бор?", OutputLanguage.RU))
    }

    @Test
    fun `russian and english are detected`() {
        assertEquals(OutputLanguage.RU, RouteLanguageDetector.detect("Сколько пациентов у меня сегодня?", OutputLanguage.EN))
        assertEquals(OutputLanguage.EN, RouteLanguageDetector.detect("What is my schedule for tomorrow morning?", OutputLanguage.UZ))
    }

    @Test
    fun `borrowed medical term does not flip an english sentence`() {
        assertEquals(OutputLanguage.EN, RouteLanguageDetector.detect("Open the MRI for Sherzod please", OutputLanguage.EN))
    }

    @Test
    fun `categories are deterministic`() {
        assertEquals(RequestCategory.APPOINTMENT_CANCELLATION, RequestCategoryClassifier.classify("Cancel his visit tomorrow"))
        assertEquals(RequestCategory.APPOINTMENT_RESCHEDULE, RequestCategoryClassifier.classify("Перенеси приём на пятницу"))
        assertEquals(RequestCategory.APPOINTMENT_BOOKING, RequestCategoryClassifier.classify("Book an appointment for Ali"))
        assertEquals(RequestCategory.SCHEDULE_QUERY, RequestCategoryClassifier.classify("Bugun jadval qanday?"))
        assertEquals(RequestCategory.CLINICAL_DOCUMENTATION, RequestCategoryClassifier.classify("Start dictation for this visit"))
        assertEquals(RequestCategory.GENERAL_ASSISTANCE, RequestCategoryClassifier.classify("Hey Shifa"))
    }
}
