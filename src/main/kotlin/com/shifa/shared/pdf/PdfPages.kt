package com.shifa.shared.pdf

/**
 * A PDF drawn from simple commands, ported from the doctor app (`utils/PdfPages.kt`) so the server's 025-2 and treatment
 * plan PDFs look like the ones the app made on the device (BE-01). Coordinates are points from the page's top-left
 * corner (y grows downwards, as in the app; [PdfBox] flips it). Grey levels: 0 black, 1 white.
 */
sealed interface PdfOp {
    /** Text wrapped inside [width] from ([x], [y]) down; [align] within the width. */
    data class Text(
        val x: Float,
        val y: Float,
        val width: Float,
        val text: String,
        val size: Float,
        val bold: Boolean = false,
        val gray: Float = 0f,
        val align: PdfAlign = PdfAlign.Start,
    ) : PdfOp

    data class Rect(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val fill: Float? = null,
        val stroke: Float? = null,
        val strokeWidth: Float = 0.5f,
    ) : PdfOp

    data class Line(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val gray: Float = 0.6f,
        val width: Float = 1f,
    ) : PdfOp

    /**
     * One line in the handwriting face (Sacramento, SIL OFL 1.1), for a typed signature; not wrapped, so keep it short.
     * A text the face can't draw (Cyrillic) falls back to the regular face.
     */
    data class Script(
        val x: Float,
        val y: Float,
        val text: String,
        val size: Float,
        val gray: Float = 0.06f,
    ) : PdfOp

    /** An image (PNG or JPEG bytes) fitted inside the box, aspect kept, centred. */
    class Image(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val bytes: ByteArray,
    ) : PdfOp
}

enum class PdfAlign { Start, Center, End }

class PdfPage(
    val width: Float,
    val height: Float,
    val ops: List<PdfOp>,
)

/** The height a text takes wrapped inside a width, as [PdfBox] draws it. */
typealias PdfMeasure = (text: String, size: Float, bold: Boolean, width: Float) -> Float

/**
 * Text flowing down pages (the app's `utils/PdfFlow.kt`): the ops of the current page, the pen's [y], a new page when
 * something doesn't fit, section headers that keep a line of their section, and paragraphs split between words across
 * pages.
 */
open class PdfFlow(
    val pageW: Float,
    val pageH: Float,
    val margin: Float,
    protected val measure: PdfMeasure,
) {
    protected val pages = mutableListOf<PdfPage>()
    protected var ops = mutableListOf<PdfOp>()
    protected var y = margin
    protected val contentW = pageW - 2 * margin
    protected val bottom = pageH - margin

    /** Starts the next page; a layout overrides it to close what spans pages (a bordered box) before calling super. */
    protected open fun newPage() {
        pages += PdfPage(pageW, pageH, ops)
        ops = mutableListOf()
        y = margin
    }

    /** Room for [height] more points, on a new page if this one is full (never for an empty page). */
    protected fun need(height: Float) {
        if (y + height > bottom && y > margin) newPage()
    }

    /** The pages, the current one last. */
    protected fun finish(): List<PdfPage> {
        pages += PdfPage(pageW, pageH, ops)
        return pages
    }

    /** A grey band with [title]; it keeps at least one line of its section with it. */
    protected fun sectionHeader(
        title: String,
        size: Float = 14f,
    ) {
        val h = measure(title, size, true, contentW - 16) + 12
        need(14 + h + 16)
        y += 14
        ops += PdfOp.Rect(margin, y, contentW, h, fill = 0.88f)
        ops += PdfOp.Text(margin + 8, y + 6, contentW - 16, title, size, bold = true)
        y += h + 4
    }

    /** A paragraph, split between words across pages when it doesn't fit (long notes). */
    protected fun paragraph(
        text: String,
        size: Float = 11f,
        bold: Boolean = false,
    ) {
        var rest = text
        while (rest.isNotEmpty()) {
            val h = measure(rest, size, bold, contentW)
            if (y + h <= bottom) {
                ops += PdfOp.Text(margin, y, contentW, rest, size, bold = bold)
                y += h + 2
                return
            }
            val words = rest.split(' ')
            val fit = fitWords(words, size, bold, contentW, bottom - y)
            if (fit == 0) {
                if (y == margin) {
                    // One word taller than a page: drawn as it is (it can't be split by words).
                    ops += PdfOp.Text(margin, y, contentW, rest, size, bold = bold)
                    y += h + 2
                    return
                }
                newPage()
                continue
            }
            ops += PdfOp.Text(margin, y, contentW, words.take(fit).joinToString(" "), size, bold = bold)
            newPage()
            rest = words.drop(fit).joinToString(" ")
        }
    }

    /** How many of [words] fit in [room] points of height at [width] (binary search; 0 when not even one). */
    protected fun fitWords(
        words: List<String>,
        size: Float,
        bold: Boolean,
        width: Float,
        room: Float,
    ): Int {
        var fit = 0
        var lo = 1
        var hi = words.size
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (measure(words.take(mid).joinToString(" "), size, bold, width) <= room) {
                fit = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return fit
    }
}
