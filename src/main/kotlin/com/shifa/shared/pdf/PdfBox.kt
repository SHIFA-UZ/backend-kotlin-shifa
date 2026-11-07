package com.shifa.shared.pdf

import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.slf4j.LoggerFactory

/**
 * Measures and draws [PdfPage]s with PDFBox and the bundled DejaVu Sans faces (Cyrillic and Uzbek Latin). One instance
 * is one document: lay the pages out with [measure], then [render] them once. Not thread-safe (PDFBox fonts aren't).
 *
 * Text wraps like the app's Android renderer (`StaticLayout`, simple break strategy): greedy by words, a word wider
 * than the line broken between characters, `\n` a hard break; a line is the face's ascent plus descent high.
 */
class PdfBox : AutoCloseable {
    private val doc = PDDocument()
    private val regular = PDType0Font.load(doc, REGULAR.inputStream())
    private val boldFace = PDType0Font.load(doc, BOLD.inputStream())
    private val supported = HashMap<Pair<Boolean, Int>, Boolean>()
    private val scriptFace by lazy { PDType0Font.load(doc, SCRIPT.inputStream()) }

    /** The height [text] takes wrapped inside [width]. */
    fun measure(
        text: String,
        size: Float,
        bold: Boolean,
        width: Float,
    ): Float = if (text.isEmpty()) 0f else ceil(lines(text, size, bold, width).size * lineHeight(bold, size))

    /** The pages as one PDF. */
    fun render(pages: List<PdfPage>): ByteArray {
        pages.forEach { p ->
            val page = PDPage(PDRectangle(p.width, p.height))
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs -> p.ops.forEach { draw(cs, p.height, it) } }
        }
        return ByteArrayOutputStream().use {
            doc.save(it)
            it.toByteArray()
        }
    }

    override fun close() = doc.close()

    private fun font(bold: Boolean) = if (bold) boldFace else regular

    private fun lineHeight(
        bold: Boolean,
        size: Float,
    ): Float = font(bold).fontDescriptor.let { (it.ascent - it.descent) / 1000f * size }

    private fun ascent(
        bold: Boolean,
        size: Float,
    ): Float = font(bold).fontDescriptor.ascent / 1000f * size

    private fun width(
        text: String,
        size: Float,
        bold: Boolean,
    ): Float = font(bold).getStringWidth(text) / 1000f * size

    /** [text] without what the face can't draw (an emoji, a control character): those become "?" or a space. */
    private fun clean(
        text: String,
        bold: Boolean,
    ): String {
        val out = StringBuilder(text.length)
        text.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ').codePoints().forEach { cp ->
            when {
                cp == '\n'.code -> out.append('\n')
                Character.isISOControl(cp) -> Unit
                hasGlyph(bold, cp) -> out.appendCodePoint(cp)
                else -> out.append('?')
            }
        }
        return out.toString()
    }

    private fun hasGlyph(
        bold: Boolean,
        cp: Int,
    ): Boolean = supported.getOrPut(bold to cp) {
        runCatching { font(bold).encode(String(Character.toChars(cp))) }.isSuccess
    }

    /** [text] broken into lines no wider than [maxWidth]. */
    private fun lines(
        text: String,
        size: Float,
        bold: Boolean,
        maxWidth: Float,
    ): List<String> {
        val out = mutableListOf<String>()
        clean(text, bold).split('\n').forEach { paragraph ->
            var line = ""
            paragraph.split(' ').forEach { word ->
                val joined = if (line.isEmpty()) word else "$line $word"
                when {
                    width(joined.trimEnd(), size, bold) <= maxWidth -> line = joined
                    else -> {
                        if (line.isNotEmpty()) out += line.trimEnd()
                        line = ""
                        // A word wider than the line: as many characters as fit, then the rest.
                        var rest = word
                        while (rest.isNotEmpty() && width(rest, size, bold) > maxWidth) {
                            var n = rest.length - 1
                            while (n > 1 && width(rest.take(n), size, bold) > maxWidth) n--
                            out += rest.take(n)
                            rest = rest.drop(n)
                        }
                        line = rest
                    }
                }
            }
            out += line.trimEnd()
        }
        return out
    }

    private fun gray(g: Float) = g.coerceIn(0f, 1f)

    private fun draw(
        cs: PDPageContentStream,
        pageH: Float,
        op: PdfOp,
    ) {
        when (op) {
            is PdfOp.Text -> {
                if (op.text.isEmpty()) return
                val g = gray(op.gray)
                cs.setNonStrokingColor(g, g, g)
                val lh = lineHeight(op.bold, op.size)
                val ascent = ascent(op.bold, op.size)
                lines(op.text, op.size, op.bold, op.width).forEachIndexed { i, line ->
                    if (line.isEmpty()) return@forEachIndexed
                    val dx = when (op.align) {
                        PdfAlign.Start -> 0f
                        PdfAlign.Center -> (op.width - width(line, op.size, op.bold)) / 2
                        PdfAlign.End -> op.width - width(line, op.size, op.bold)
                    }
                    cs.beginText()
                    cs.setFont(font(op.bold), op.size)
                    cs.newLineAtOffset(op.x + dx, pageH - (op.y + ascent + i * lh))
                    cs.showText(line)
                    cs.endText()
                }
            }
            is PdfOp.Script -> {
                val text = clean(op.text, false).replace('\n', ' ')
                if (text.isBlank()) return
                val face = if (runCatching { scriptFace.encode(text) }.isSuccess) scriptFace else regular
                val g = gray(op.gray)
                cs.setNonStrokingColor(g, g, g)
                cs.beginText()
                cs.setFont(face, op.size)
                cs.newLineAtOffset(op.x, pageH - (op.y + face.fontDescriptor.ascent / 1000f * op.size))
                cs.showText(text)
                cs.endText()
            }
            is PdfOp.Rect -> {
                op.fill?.let {
                    val g = gray(it)
                    cs.setNonStrokingColor(g, g, g)
                    cs.addRect(op.x, pageH - op.y - op.height, op.width, op.height)
                    cs.fill()
                }
                op.stroke?.let {
                    val g = gray(it)
                    cs.setStrokingColor(g, g, g)
                    cs.setLineWidth(op.strokeWidth)
                    cs.addRect(op.x, pageH - op.y - op.height, op.width, op.height)
                    cs.stroke()
                }
            }
            is PdfOp.Line -> {
                val g = gray(op.gray)
                cs.setStrokingColor(g, g, g)
                cs.setLineWidth(op.width)
                cs.moveTo(op.x1, pageH - op.y1)
                cs.lineTo(op.x2, pageH - op.y2)
                cs.stroke()
            }
            is PdfOp.Image -> {
                // An unreadable image (a broken signature) is left out; the rest of the document still renders.
                val image = runCatching { PDImageXObject.createFromByteArray(doc, op.bytes, "image") }
                    .onFailure { log.warn("Skipping an unreadable image in a PDF: {}", it.javaClass.simpleName) }
                    .getOrNull() ?: return
                val scale = minOf(op.width / image.width, op.height / image.height)
                val w = image.width * scale
                val h = image.height * scale
                val left = op.x + (op.width - w) / 2
                val top = op.y + (op.height - h) / 2
                cs.drawImage(image, left, pageH - top - h, w, h)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PdfBox::class.java)
        private val REGULAR: ByteArray = font("/fonts/DejaVuSans.ttf")
        private val BOLD: ByteArray = font("/fonts/DejaVuSans-Bold.ttf")
        private val SCRIPT: ByteArray by lazy { font("/fonts/Sacramento-Regular.ttf") }

        private fun font(path: String): ByteArray =
            PdfBox::class.java.getResourceAsStream(path)?.use { it.readBytes() }
                ?: error("Missing classpath font $path")

        /** Lays out with a fresh document's fonts and renders it: `PdfBox.make { measure -> pages(measure) }`. */
        fun make(layout: (PdfMeasure) -> List<PdfPage>): ByteArray = PdfBox().use { it.render(layout(it::measure)) }
    }
}

/** The PDF languages: `en`, `uz`, `ru`. */
object PdfLanguage {
    val SUPPORTED = setOf("en", "uz", "ru")

    /** A `lang` parameter checked: null when not given; 400 (IllegalArgumentException) for another language. */
    fun parse(raw: String?): String? {
        val lang = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        require(lang in SUPPORTED) { "lang must be one of en, uz, ru" }
        return lang
    }

    /** A stored language ("ru", "uz-Latn", null) as a PDF language; English otherwise. */
    fun of(raw: String?): String = raw?.trim()?.lowercase()?.take(2)?.takeIf { it in SUPPORTED } ?: "en"
}
