package com.shifa.documents.application

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.slf4j.LoggerFactory
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import java.io.InputStream

/**
 * Extracts plain text from PDF resources for AI briefing.
 * Handles multilingual content (text is passed through as-is to the model).
 *
 * Hard limits keep PDFBox work from spiking CPU/memory on large or malicious files.
 */
@Service
class PdfTextExtractionService {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Refuse to fully buffer PDFs larger than this. */
        const val MAX_BYTES = 4 * 1024 * 1024
        /** Cap pages stripped for briefing-style extraction. */
        const val MAX_PAGES = 20
    }

    /**
     * Extract text from a PDF resource. Returns empty string on failure
     * (missing file, not a PDF, password-protected, etc.).
     */
    fun extractText(resource: Resource?): String {
        if (resource == null || !resource.exists() || !resource.isReadable) return ""
        val contentLength = runCatching { resource.contentLength() }.getOrDefault(-1L)
        if (contentLength > MAX_BYTES) {
            log.info("Skipping PDF extraction: contentLength={} exceeds max={}", contentLength, MAX_BYTES)
            return ""
        }
        return resource.inputStream.use { input -> extractTextFromStream(input) }
    }

    /**
     * Extract text from an input stream (PDF). Caller is responsible for closing the stream
     * if not using use { }; this overload is for use with Resource.inputStream.use.
     */
    fun extractTextFromStream(inputStream: InputStream): String {
        return try {
            val bytes = inputStream.readNBytes(MAX_BYTES + 1)
            if (bytes.size > MAX_BYTES) {
                log.info("Skipping PDF extraction: streamed size exceeds max={}", MAX_BYTES)
                return ""
            }
            Loader.loadPDF(bytes).use { doc ->
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
            val pageCount = doc.numberOfPages
            if (pageCount > MAX_PAGES) {
                stripper.setStartPage(1)
                stripper.setEndPage(MAX_PAGES)
            }
            stripper.getText(doc).trim()
        } catch (e: Exception) {
            log.warn("PDFTextStripper failed: {}", e.message)
            ""
        }
    }
}
