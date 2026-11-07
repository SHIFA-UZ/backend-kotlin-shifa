package com.shifa.clinics.application

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TreatmentPlanPdfTest {

    private fun line(id: Long, title: String, tooth: String?) =
        PlanPdfLine(id, title, 1, 150_000_00, 0, "UZS", id.toInt(), "PLANNED", 150_000_00, tooth)

    private fun data(notes: String? = "Контроль через месяц", lines: List<PlanPdfLine> = listOf(line(1, "Пломба", "16"), line(2, "Консультация", null))) =
        TreatmentPlanPdfData(
            summary = PlanPdfSummary(
                id = 42, patientId = 7, patientName = "Иванов Иван", title = "Санация", diagnosis = "Кариес",
                status = "ACTIVE", notes = notes, symptoms = listOf("боль"), totalMinor = 300_000_00,
                paidMinor = 100_000_00, owedMinor = 200_000_00, currency = "UZS",
            ),
            lines = lines,
            schedules = listOf(
                PlanPdfSchedule(3, "ACTIVE", 200_000_00, "UZS", listOf(PlanPdfInstallment(1, 200_000_00, "UZS", "2026-12-01", "PENDING"))),
            ),
            clinic = "Shifa Demo Clinic",
            generatedAt = "06.11.2026 14:05",
            created = "01.11.2026",
            updated = null,
            doctors = "Aziza Karimova",
        )

    private fun text(bytes: ByteArray) = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) to it.numberOfPages }

    @Test
    fun `renders the plan with Russian labels, Cyrillic text, teeth groups and money`() {
        val bytes = TreatmentPlanPdf.pdf(data(), TreatmentPlanPdfLabels.of("ru"))
        assertEquals("%PDF", String(bytes.copyOfRange(0, 4)))
        val (text, _) = text(bytes)
        listOf(
            "План лечения · Санация", "ID плана: #42", "Создано: 06.11.2026 14:05", "Иванов Иван", "Зуб 16 (FDI)",
            "Другие услуги", "Пломба", "Консультация", "Активно", "Запланировано", "150 000 UZS",
            "График #3 · Активно", "Ожидает", "Подпись пациента", "Стр. 1 / ",
        ).forEach { assertTrue(text.contains(it), "missing \"$it\" in:\n$text") }
    }

    @Test
    fun `labels exist in every language`() {
        listOf("en" to "Treatment Plan", "uz" to "Davolash rejasi", "ru" to "План лечения", null to "Treatment Plan").forEach { (lang, title) ->
            val (text, _) = text(TreatmentPlanPdf.pdf(data(), TreatmentPlanPdfLabels.of(lang)))
            assertTrue(text.contains(title), "$lang: $text")
        }
    }

    @Test
    fun `many lines flow onto more pages with the page in every footer`() {
        val lines = (1L..80L).map { line(it, "Услуга номер $it", null) }
        val (text, pages) = text(TreatmentPlanPdf.pdf(data(lines = lines), TreatmentPlanPdfLabels.of("en")))
        assertTrue(pages >= 2, "expected several pages, got $pages")
        assertTrue(text.contains("Page 1 / $pages") && text.contains("Page $pages / $pages"))
        assertTrue(text.contains("Услуга номер 80"))
    }

    @Test
    fun `money is formatted like the app`() {
        assertEquals("1 250 000 UZS", TreatmentPlanPdf.Money.format(1_250_000_00, "uzs"))
        assertEquals("1 250.50 USD", TreatmentPlanPdf.Money.format(125_050, "USD"))
        assertEquals("−5 UZS", TreatmentPlanPdf.Money.format(-500, "UZS"))
    }
}
