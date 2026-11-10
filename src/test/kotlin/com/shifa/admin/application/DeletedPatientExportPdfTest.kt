package com.shifa.admin.application

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeletedPatientExportPdfTest {
    @Test
    fun `keeps Cyrillic and Uzbek Latin text and spans pages`() {
        val json = buildString {
            appendLine("{")
            appendLine("  \"fullName\" : \"Иванов Иван Ўғли\",")
            appendLine("  \"address\" : \"Toshkent, Oʻzbekiston koʻchasi, Gʻalaba mahallasi\",")
            repeat(200) { appendLine("  \"note$it\" : \"Қабул $it\",") }
            append("}")
        }
        val bytes = DeletedPatientExportPdf.render("Deleted Patient Export (Legal/GDPR)", "patientProfileId=7", json)
        val (text, pages) = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) to it.numberOfPages }
        listOf("Иванов Иван Ўғли", "Oʻzbekiston koʻchasi", "Gʻalaba", "Қабул 199", "patientProfileId=7").forEach {
            assertTrue(text.contains(it), "missing: $it")
        }
        assertFalse(text.contains("?"), "a character was replaced by ?")
        assertTrue(pages > 1)
    }

    @Test
    fun `a very long unbroken value is wrapped and paginated, not lost`() {
        fun render(value: String) = DeletedPatientExportPdf.render("Export", "patientProfileId=7", "{\n  \"body\" : \"$value\"\n}")
        fun read(bytes: ByteArray) = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) to it.numberOfPages }
        val token = (0 until 10_000).joinToString("") { ('a' + it % 26).toString() }
        val (shortText, shortPages) = read(render("short"))
        val (text, pages) = read(render(token))
        assertTrue(shortText.contains("short"))
        assertTrue(text.filterNot { it.isWhitespace() }.contains("\"body\":\"$token\"}"), "part of the value is missing")
        assertTrue(pages > shortPages && pages >= 2, "pages: $pages")
    }
}
