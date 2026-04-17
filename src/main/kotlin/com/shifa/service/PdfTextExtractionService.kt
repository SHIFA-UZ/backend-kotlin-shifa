package com.shifa.service

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.slf4j.LoggerFactory
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import java.io.InputStream

/**
 * Extracts plain text from PDF resources for AI briefing.
 * Handles multilingual content (text is passed through as-is to the model).
 */
@Service
class PdfTextExtractionService {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Extract text from a PDF resource. Returns empty string on failure
     * (missing file, not a PDF, password-protected, etc.).
     */
    fun extractText(resource: Resource?): String {
        if (resource == null || !resource.exists() || !resource.isReadable) return ""
        return resource.inputStream.use { input -> extractTextFromStream(input) }
    }

    /**
     * Extract text from an input stream (PDF). Caller is responsible for closing the stream
     * if not using use { }; this overload is for use with Resource.inputStream.use.
     */
    fun extractTextFromStream(inputStream: InputStream): String {
        return try {
            Loader.loadPDF(inputStream.readAllBytes()).use { doc ->
                extractTextFromDocument(doc)
            }
        } catch (e: Exception) {
            log.warn("PDF text extraction failed: {}", e.message)
            ""
        }
    }

    private fun extractTextFromDocument(doc: PDDocument): String {
        return try {
            val stripper = org.apache.pdfbox.text.PDFTextStripper()
            stripper.setSortByPosition(true)
            stripper.getText(doc).trim()
        } catch (e: Exception) {
            log.warn("PDFTextStripper failed: {}", e.message)
            ""
        }
    }
}
