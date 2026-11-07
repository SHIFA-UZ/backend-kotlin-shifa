package com.shifa.clinics.application

import com.shifa.shared.pdf.PdfAlign
import com.shifa.shared.pdf.PdfBox
import com.shifa.shared.pdf.PdfFlow
import com.shifa.shared.pdf.PdfMeasure
import com.shifa.shared.pdf.PdfOp
import com.shifa.shared.pdf.PdfPage

/** The plan as the PDF shows it: the fields of `GET /treatment-plans/{id}/detail`, money in minor units. */
data class PlanPdfSummary(
    val id: Long,
    val patientId: Long?,
    val patientName: String?,
    val title: String?,
    val diagnosis: String?,
    // TreatmentPlan.Status name.
    val status: String,
    val notes: String?,
    val symptoms: List<String>,
    val totalMinor: Long,
    val paidMinor: Long,
    val owedMinor: Long,
    val currency: String,
)

data class PlanPdfLine(
    val id: Long,
    val title: String,
    val quantity: Int,
    val unitPriceMinor: Long,
    val discountMinor: Long,
    val currency: String,
    val sortOrder: Int,
    // TreatmentPlanLine.LineStatus name.
    val status: String,
    val lineTotalMinor: Long,
    // The FDI tooth from the line's specialty metadata; null for a line not tied to a tooth.
    val tooth: String?,
)

data class PlanPdfInstallment(
    val sequenceNumber: Int,
    val amountMinor: Long,
    val currency: String,
    val dueDate: String,
    val status: String,
)

data class PlanPdfSchedule(
    val id: Long,
    val status: String?,
    val totalAmountMinor: Long,
    val currency: String,
    val items: List<PlanPdfInstallment>,
)

/** What the plan PDF shows besides the plan: dates already in the clinic's zone ("06.11.2026 14:05"). */
data class TreatmentPlanPdfData(
    val summary: PlanPdfSummary,
    val lines: List<PlanPdfLine>,
    val schedules: List<PlanPdfSchedule>,
    val clinic: String?,
    val generatedAt: String,
    val created: String?,
    val updated: String?,
    // The doctors as the detail lists them ("Aziza Karimova, Bobur Aliyev").
    val doctors: String?,
)

/**
 * The treatment plan document (BE-01), ported from the doctor app's `data/treatmentplans/TreatmentPlanPdf.kt` so it
 * looks the same: portrait A4, the header (SHIFA, the clinic, the plan id, when it was made), the title band, the
 * summary, symptoms, notes, the lines (grouped by tooth when any line has one, "Other services" last), the installment
 * schedules, the patient's acknowledgement with signature and date lines, and a footer with the page on every page.
 */
object TreatmentPlanPdf {
    // Portrait A4 in points, 40 pt margins.
    const val PAGE_W = 595f
    const val PAGE_H = 842f
    const val MARGIN = 40f
    private const val LABEL_W = 150f
    private const val SIGNATURE_H = 36f
    private const val STATUS_GAP = 8f

    // #, service (the rest of the width), qty, unit price, discount, line total, status.
    private val LINE_COLUMNS = listOf(22f, 0f, 40f, 76f, 66f, 76f, 86f)

    // #, due date, amount (the status takes the rest).
    private val SCHEDULE_COLUMNS = listOf(30f, 140f, 140f)

    fun pdf(
        data: TreatmentPlanPdfData,
        labels: TreatmentPlanPdfLabels,
    ): ByteArray = PdfBox.make { pages(data, labels, it) }

    internal fun pages(
        data: TreatmentPlanPdfData,
        labels: TreatmentPlanPdfLabels,
        measure: PdfMeasure,
    ): List<PdfPage> = Layout(data, labels, measure).run()

    private class Layout(
        private val d: TreatmentPlanPdfData,
        private val l: TreatmentPlanPdfLabels,
        measure: PdfMeasure,
    ) : PdfFlow(PAGE_W, PAGE_H, MARGIN, measure) {
        private val p = d.summary

        fun run(): List<PdfPage> {
            header()
            sectionHeader(l.title + (p.title?.takeIf(String::isNotBlank)?.let { " · $it" } ?: ""), 15f)
            summary()
            p.symptoms.filter(String::isNotBlank).takeIf { it.isNotEmpty() }?.let { list ->
                sectionHeader(l.symptoms)
                list.forEach { paragraph("• ${it.trim()}") }
            }
            p.notes?.trim()?.takeIf(String::isNotEmpty)?.let { notes ->
                sectionHeader(l.notes)
                notes.split('\n').forEach { line -> if (line.isBlank()) y += 6 else paragraph(line) }
            }
            if (d.lines.isNotEmpty()) lines()
            if (d.schedules.isNotEmpty()) installments()
            signature()
            val all = finish()
            return all.mapIndexed { i, page -> PdfPage(page.width, page.height, page.ops + footer(i + 1, all.size)) }
        }

        private fun header() {
            val half = contentW / 2
            ops += PdfOp.Text(margin, y, half, "SHIFA", 20f, bold = true)
            var left = y + measure("SHIFA", 20f, true, half) + 2
            d.clinic?.takeIf { it.isNotBlank() }?.let {
                ops += PdfOp.Text(margin, left, half, it, 10f, gray = 0.35f)
                left += measure(it, 10f, false, half)
            }
            val id = l.planId(p.id)
            val generated = l.generated(d.generatedAt)
            ops += PdfOp.Text(margin + half, y, half, id, 10f, align = PdfAlign.End)
            val right = y + measure(id, 10f, false, half) + 2
            ops += PdfOp.Text(margin + half, right, half, generated, 10f, gray = 0.35f, align = PdfAlign.End)
            y = maxOf(left, right + measure(generated, 10f, false, half)) + 8
            ops += PdfOp.Line(margin, y, pageW - margin, y, gray = 0.5f, width = 1.5f)
            y += 4
        }

        /** Label and value per row. */
        private fun summary() {
            sectionHeader(l.summary)
            val rows =
                listOfNotNull(
                    l.patient to (p.patientName ?: "—"),
                    p.patientId?.let { l.patientId to "#$it" },
                    l.planTitle to (p.title?.takeIf(String::isNotBlank) ?: "—"),
                    l.diagnosis to (p.diagnosis?.takeIf(String::isNotBlank) ?: "—"),
                    d.doctors?.let { l.doctors to it },
                    l.status to l.planStatus(p.status),
                    l.total to Money.format(p.totalMinor, p.currency),
                    l.paid to Money.format(p.paidMinor, p.currency),
                    l.outstanding to Money.format(p.owedMinor, p.currency),
                    d.created?.let { l.created to it },
                    d.updated?.let { l.updated to it },
                )
            val valueW = contentW - LABEL_W
            rows.forEach { (label, value) ->
                val h = maxOf(measure(value, 11f, false, valueW), measure(label, 11f, true, LABEL_W)) + 4
                // A value longer than half a page (a pasted diagnosis): the label, then the text flowing on.
                if (h > (bottom - margin) / 2) {
                    need(measure(label, 11f, true, contentW) + 4)
                    ops += PdfOp.Text(margin, y, contentW, label, 11f, bold = true, gray = 0.3f)
                    y += measure(label, 11f, true, contentW) + 2
                    paragraph(value)
                    return@forEach
                }
                need(h)
                ops += PdfOp.Text(margin, y, LABEL_W, label, 11f, bold = true, gray = 0.3f)
                ops += PdfOp.Text(margin + LABEL_W, y, valueW, value, 11f)
                y += h
            }
        }

        /** The lines: per tooth when any has one (teeth in number order, "Other services" last), else one table. */
        private fun lines() {
            sectionHeader(l.procedures)
            val lines = d.lines.sortedWith(compareBy<PlanPdfLine> { it.sortOrder }.thenBy { it.id })
            if (lines.none { it.tooth != null }) return table(null, lines)
            val byTooth = lines.groupBy { it.tooth }
            byTooth.keys.filterNotNull().sortedWith(compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it })).forEach {
                table(l.tooth(it), byTooth.getValue(it))
            }
            byTooth[null]?.let { table(l.otherServices, it) }
        }

        /** A bold heading kept on the page with [keep] points of what follows it (the table's head and first row). */
        private fun subheading(
            text: String,
            keep: Float,
        ) {
            val h = measure(text, 11f, true, contentW)
            need(6 + h + 2 + keep)
            y += 6
            ops += PdfOp.Text(margin, y, contentW, text, 11f, bold = true)
            y += h + 2
        }

        private fun lineHead() = listOf("#", l.service, l.quantity, l.unitPrice, l.discount, l.lineTotal, l.lineStatus)

        private fun lineCells(
            n: Int,
            line: PlanPdfLine,
        ) = listOf(
            "$n",
            line.title,
            "${line.quantity}",
            Money.format(line.unitPriceMinor, line.currency),
            if (line.discountMinor > 0) Money.format(line.discountMinor, line.currency) else "—",
            Money.format(line.lineTotalMinor, line.currency),
            l.lineStatusOf(line.status),
        )

        private fun table(
            heading: String?,
            rows: List<PlanPdfLine>,
        ) {
            val head = lineHead()
            heading?.let {
                subheading(it, rowHeight(head, true) + (rows.firstOrNull()?.let { r -> rowHeight(lineCells(1, r), false) } ?: 0f))
            }
            row(head, bold = true)
            rows.forEachIndexed { i, line -> row(lineCells(i + 1, line)) }
        }

        private fun lineWidths() = LINE_COLUMNS.toMutableList().also { it[1] = contentW - LINE_COLUMNS.sum() }

        // The status starts a little after the right-aligned money before it.
        private fun pad(i: Int) = if (i == 6) STATUS_GAP else 0f

        private fun rowHeight(
            cells: List<String>,
            bold: Boolean,
        ): Float {
            val widths = lineWidths()
            return cells.indices.maxOf { measure(cells[it], 9f, bold, widths[it] - 4 - pad(it)) } + 4
        }

        /** One row of the lines table; the service wraps, money is right-aligned. */
        private fun row(
            cells: List<String>,
            bold: Boolean = false,
        ) {
            val widths = lineWidths()
            val h = rowHeight(cells, bold)
            need(h)
            var x = margin
            cells.forEachIndexed { i, text ->
                val align = if (i in 3..5) PdfAlign.End else PdfAlign.Start
                ops += PdfOp.Text(x + pad(i), y, widths[i] - 4 - pad(i), text, 9f, bold = bold, align = align)
                x += widths[i]
            }
            y += h
            if (bold) ops += PdfOp.Line(margin, y - 2, pageW - margin, y - 2, gray = 0.75f, width = 0.5f)
        }

        private fun installments() {
            sectionHeader(l.installments)
            d.schedules.forEach { plan ->
                val head = listOf("#", l.dueDate, l.amountDue, l.partStatus)
                val items =
                    plan.items.sortedBy { it.sequenceNumber }.map {
                        listOf("${it.sequenceNumber}", it.dueDate, Money.format(it.amountMinor, it.currency), l.installmentStatus(it.status))
                    }
                subheading(
                    l.schedule(plan.id, l.scheduleStatus(plan.status.orEmpty()), Money.format(plan.totalAmountMinor, plan.currency)),
                    scheduleHeight(head, true) + (items.firstOrNull()?.let { scheduleHeight(it, false) } ?: 0f),
                )
                scheduleRow(head, bold = true)
                items.forEach { scheduleRow(it) }
            }
        }

        private fun scheduleWidths() = SCHEDULE_COLUMNS + (contentW - SCHEDULE_COLUMNS.sum())

        private fun scheduleHeight(
            cells: List<String>,
            bold: Boolean,
        ): Float {
            val widths = scheduleWidths()
            return cells.indices.maxOf { measure(cells[it], 9f, bold, widths[it] - 4) } + 4
        }

        private fun scheduleRow(
            cells: List<String>,
            bold: Boolean = false,
        ) {
            val widths = scheduleWidths()
            val h = scheduleHeight(cells, bold)
            need(h)
            var x = margin
            cells.forEachIndexed { i, text ->
                ops += PdfOp.Text(x, y, widths[i] - 4, text, 9f, bold = bold, align = if (i == 2) PdfAlign.End else PdfAlign.Start)
                x += widths[i]
            }
            y += h
        }

        /** The acknowledgement, the patient's name, and lines to sign and date (one block, never split). */
        private fun signature() {
            val half = contentW / 2 - 8
            val note = measure(l.acknowledgementNote, 10f, false, contentW)
            val bandH = 14 + measure(l.acknowledgement, 14f, true, contentW - 16) + 12 + 4
            need(bandH + note + 24 + SIGNATURE_H + 20)
            sectionHeader(l.acknowledgement)
            ops += PdfOp.Text(margin, y, contentW, l.acknowledgementNote, 10f, gray = 0.25f)
            y += note + 6
            ops += PdfOp.Text(margin, y, contentW, "${l.patient}: ${p.patientName ?: "—"}", 10f, bold = true)
            y += 14 + SIGNATURE_H
            val right = margin + contentW - half
            ops += PdfOp.Line(margin, y, margin + half, y, gray = 0.3f)
            ops += PdfOp.Line(right, y, right + half, y, gray = 0.3f)
            y += 4
            ops += PdfOp.Text(margin, y, half, l.patientSignature, 10f, gray = 0.35f)
            ops += PdfOp.Text(right, y, half, l.date, 10f, gray = 0.35f)
            y += 16
        }

        private fun footer(
            page: Int,
            pages: Int,
        ): List<PdfOp> {
            val fy = pageH - margin + 12
            return listOf(
                PdfOp.Line(margin, fy - 4, pageW - margin, fy - 4, gray = 0.8f),
                PdfOp.Text(margin, fy, contentW * 0.75f, l.footer, 8f, gray = 0.45f),
                PdfOp.Text(margin + contentW * 0.75f, fy, contentW * 0.25f, l.page(page, pages), 8f, gray = 0.45f, align = PdfAlign.End),
            )
        }
    }

    /**
     * The app's money formatter (`utils/Money`): UZS in whole sums ("1 250 000 UZS", tiyin rounded), other currencies
     * with two decimals; thousands grouped with a narrow no-break space, the code after a no-break space.
     */
    internal object Money {
        private const val GROUP = ' '
        private const val NBSP = ' '
        private const val MINUS = '−'

        fun format(
            minor: Long,
            currency: String,
        ): String {
            val code = currency.uppercase()
            val sign = if (minor < 0) MINUS.toString() else ""
            val whole = kotlin.math.abs(minor / 100)
            val cents = kotlin.math.abs(minor % 100)
            val number =
                if (code == "UZS") group(if (cents >= 50) whole + 1 else whole) else "${group(whole)}.${cents.toString().padStart(2, '0')}"
            return "$sign$number$NBSP$code"
        }

        private fun group(value: Long): String = value.toString().reversed().chunked(3).joinToString(GROUP.toString()).reversed()
    }
}
