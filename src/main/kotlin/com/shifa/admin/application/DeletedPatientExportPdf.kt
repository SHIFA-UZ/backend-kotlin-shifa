package com.shifa.admin.application

import com.shifa.shared.pdf.PdfBox
import com.shifa.shared.pdf.PdfFlow
import com.shifa.shared.pdf.PdfMeasure
import com.shifa.shared.pdf.PdfOp
import com.shifa.shared.pdf.PdfPage

/**
 * The deleted-patient legal export as a PDF: a title, the file label and the export text line by line, in the bundled
 * DejaVu Sans faces of [PdfBox], so Cyrillic and Uzbek Latin names and notes stay readable.
 */
object DeletedPatientExportPdf {
    private const val A4_WIDTH = 595f
    private const val A4_HEIGHT = 842f
    private const val MARGIN = 50f
    private const val TITLE_SIZE = 14f
    private const val TEXT_SIZE = 9f

    /** More characters than one line of the page holds at [TEXT_SIZE]; bounds the search for where a line ends. */
    private const val MAX_LINE_CHARS = 160
    private const val NBSP = ' '

    fun render(title: String, fileLabel: String, text: String): ByteArray =
        PdfBox.make { measure -> Flow(measure).pages(title, fileLabel, text) }

    private class Flow(measure: PdfMeasure) : PdfFlow(A4_WIDTH, A4_HEIGHT, MARGIN, measure) {
        fun pages(title: String, fileLabel: String, text: String): List<PdfPage> {
            paragraph(title, TITLE_SIZE, bold = true)
            paragraph(fileLabel)
            ops += PdfOp.Line(margin, y + 4, pageW - margin, y + 4)
            y += 10
            // Leading spaces (the JSON indentation) as no-break spaces: the word wrap would drop them.
            text.lineSequence().forEach { line ->
                val indent = line.length - line.trimStart(' ').length
                printedLines(NBSP.toString().repeat(indent) + line.drop(indent)).forEach { paragraph(it, TEXT_SIZE) }
            }
            return finish()
        }

        /**
         * [line] cut into pieces of one printed line each, at a space where there is one and inside a long unbroken
         * value otherwise, so every piece can move to the next page and nothing runs off the bottom.
         */
        private fun printedLines(line: String): List<String> {
            val oneLine = measure("x", TEXT_SIZE, false, contentW)
            fun fits(piece: String) = measure(piece, TEXT_SIZE, false, contentW) <= oneLine
            val out = mutableListOf<String>()
            var rest = line
            while (rest.isNotEmpty()) {
                var lo = 1
                var hi = minOf(rest.length, MAX_LINE_CHARS)
                while (lo < hi) {
                    val mid = (lo + hi + 1) / 2
                    if (fits(rest.take(mid))) lo = mid else hi = mid - 1
                }
                val atSpace = if (lo < rest.length) rest.lastIndexOf(' ', lo - 1) + 1 else 0
                val cut = if (atSpace > 0) atSpace else lo
                out += rest.take(cut).trimEnd(' ')
                rest = rest.drop(cut)
            }
            return out
        }
    }
}
