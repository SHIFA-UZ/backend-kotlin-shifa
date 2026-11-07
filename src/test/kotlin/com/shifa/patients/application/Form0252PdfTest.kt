package com.shifa.patients.application

import com.shifa.patients.domain.PatientForm
import com.shifa.patients.domain.PatientProfile
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import javax.imageio.ImageIO
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Form0252PdfTest {

    private fun signature(): String {
        val image = BufferedImage(60, 20, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun form(complaints: String = "Тиш оғриғи, кечаси кучаяди") = PatientForm(
        id = 5L,
        patient = PatientProfile(id = 77L, fullName = "Иванов Иван"),
        templateId = "025-2",
        date = LocalDate.of(2026, 11, 6),
        fullName = "Иванов Иван",
        age = 34,
        gender = "Erkak",
        diagnosis = "Пульпит",
        diagnosisCode = "K04.0",
        diagnosisDisplay = "Pulpitis",
        complaints = complaints,
        treatment = "Каналы пломбированы",
        doctorName = "Karimova Aziza",
        doctorClinic = "Shifa Demo Clinic",
        formNumber = 12,
        dentalChart = mapOf(
            "TOP_HIST_0_3" to "C",
            "TOP_HIST_0_date" to "2026-01-10",
            "TOP_HIST_0_doctor" to "Dr. A",
            "TOP_0_5" to "P",
            "BOTTOM_0_2" to "П",
        ),
        followups = listOf(mapOf("date" to "2026-11-20", "clinicalFindings" to "Жалоб нет", "doctorName" to "Karimova Aziza")),
        patientSignatureImage = signature(),
        patientSignedAt = Instant.parse("2026-11-06T10:00:00Z"),
    )

    private fun text(bytes: ByteArray) = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) to it.numberOfPages }

    @Test
    fun `renders the 025-2 with its Uzbek labels, Cyrillic text, the chart and the signature`() {
        val bytes = Form0252Pdf.pdf(form())
        assertEquals("%PDF", String(bytes.copyOfRange(0, 4)))
        val (text, _) = text(bytes)
        listOf(
            "STOMATOLOGIK", "KARTASI", Form0252Pdf.Labels.COMPLAINTS, Form0252Pdf.Labels.DENTAL_CHART,
            Form0252Pdf.Labels.RETURN_VISITS, Form0252Pdf.Labels.SIGNATURE, "Тиш оғриғи", "K04.0 — Pulpitis",
            "Пульпит", "Nr. 12", "06.11.2026", "10.01.2026 · Dr. A", "Жалоб нет", "34 yosh", "18", "28",
        ).forEach { assertTrue(text.contains(it), "missing \"$it\" in:\n$text") }
    }

    @Test
    fun `long text flows onto more pages`() {
        val long = (1..900).joinToString(" ") { "жалоба$it" }
        val (text, pages) = text(Form0252Pdf.pdf(form(long)))
        assertTrue(pages >= 2, "expected several pages, got $pages")
        assertTrue(text.contains("жалоба900"))
    }

    @Test
    fun `a broken signature and an emoji do not break the render`() {
        val f = form("Болит 😬 зуб").apply { patientSignatureImage = "not-base64!!" }
        val (text, _) = text(Form0252Pdf.pdf(f))
        assertTrue(text.contains("Болит ? зуб"))
    }
}
