package com.shifa.patients.application

import com.shifa.patients.domain.PatientForm
import com.shifa.shared.pdf.PdfAlign
import com.shifa.shared.pdf.PdfBox
import com.shifa.shared.pdf.PdfFlow
import com.shifa.shared.pdf.PdfMeasure
import com.shifa.shared.pdf.PdfOp
import com.shifa.shared.pdf.PdfPage
import java.util.Base64

/**
 * The 025-2 as a PDF (BE-01), ported from the doctor app's `data/forms/Form0252Pdf.kt` so it looks the same: landscape
 * A4, the official form's Uzbek labels in every language (a state medical form), the header, the patient block, every
 * section in the form's order, the dental chart (history rows with their date and doctor, then this visit's rows,
 * around the FDI numbers), the return visits table and the patient's signature. Text flows across pages; a paragraph
 * longer than a page is split between words.
 */
object Form0252Pdf {
    // Landscape A4 in points, 40 pt margins.
    const val PAGE_W = 842f
    const val PAGE_H = 595f
    const val MARGIN = 40f
    private const val LABEL_W = 200f
    private const val BLOCK_PAD = 12f
    private const val ROW_GAP = 8f
    private const val BOX = 26f
    private const val GAP = 4f
    private const val DIVIDER = 2f
    private const val ROW = BOX + 2
    private const val NOTE = 10f
    private const val NUMBERS = 24f

    fun pdf(form: PatientForm): ByteArray = PdfBox.make { pages(form, it) }

    internal fun pages(
        form: PatientForm,
        measure: PdfMeasure,
    ): List<PdfPage> = Layout(form, measure).run()

    /** "2026-03-12" (or a timestamp) as "12.03.2026"; anything else as written. */
    private fun day(raw: String): String = DentalChartLayout.displayHistoryDate(raw.take(10))

    /** A history row's caption: "10.01.2026 · Dr. A". */
    private fun caption(h: DentalHistoryRow) =
        listOf(DentalChartLayout.displayHistoryDate(h.date), h.doctor?.trim().orEmpty())
            .filter(String::isNotEmpty)
            .joinToString(" · ")

    private class Layout(
        private val form: PatientForm,
        measure: PdfMeasure,
    ) : PdfFlow(PAGE_W, PAGE_H, MARGIN, measure) {
        private val chart: Map<String, String> = form.dentalChart.orEmpty()
        private val patientId = form.patient?.id?.toString().orEmpty()

        fun run(): List<PdfPage> {
            header()
            patientBlock()
            section(Labels.COMPLAINTS, form.complaints)
            section(Labels.OBJECTIVE_EXAM, form.visualCheckup)
            // The ICD code and its name first, then the doctor's own wording.
            val icd =
                listOfNotNull(form.diagnosisCode?.trim(), form.diagnosisDisplay?.trim())
                    .filter(String::isNotEmpty)
                    .takeIf { it.size == 2 }
                    ?.joinToString(" — ")
            section(Labels.DIAGNOSIS, listOfNotNull(icd, form.diagnosis?.trim()?.ifEmpty { null }).joinToString("\n"))
            section(Labels.PAST_AND_COMORBID, form.otherIllnesses)
            section(Labels.OCCLUSION, form.occlusion)
            section(Labels.DISEASE_DEVELOPMENT, form.moreDetails)
            section(Labels.ORAL_CAVITY, form.oralCavityCondition)
            section(Labels.XRAY_LAB, form.xrayLabData)
            sectionHeader(Labels.DENTAL_CHART)
            dentalChart()
            section(Labels.TREATMENT, form.treatment)
            section(Labels.TREATMENT_RESULT, form.treatmentResult)
            section(Labels.RECOMMENDATIONS, form.recommendations)
            val followups = form.followups.orEmpty()
            if (followups.isNotEmpty()) {
                sectionHeader(Labels.RETURN_VISITS)
                followups(followups)
            }
            signature()
            return finish()
        }

        private fun header() {
            val third = contentW / 3
            val clinic = form.doctorClinic.orEmpty()
            val clinicH = measure(clinic, 11f, true, third - 8)
            ops += PdfOp.Text(MARGIN, y, third - 8, clinic, 11f, bold = true)
            ops += PdfOp.Text(MARGIN, y + clinicH + 2, third - 8, Labels.ORDER, 9f, gray = 0.4f)
            ops += PdfOp.Text(MARGIN + third, y, third, Labels.TITLE, 17f, bold = true, align = PdfAlign.Center)
            val right =
                listOf(
                    "Nr. ${form.formNumber?.toString().orEmpty()}",
                    "${Labels.DATE}: ${day(form.date.toString())}",
                    "${Labels.PATIENT_ID}: $patientId",
                )
            right.forEachIndexed { i, line ->
                ops += PdfOp.Text(MARGIN + 2 * third, y + i * 13f, third, line, 10f, align = PdfAlign.End)
            }
            val leftH = clinicH + 2 + measure(Labels.ORDER, 9f, false, third - 8)
            val h = maxOf(leftH, measure(Labels.TITLE, 17f, true, third), right.size * 13f)
            y += h + 8
            ops += PdfOp.Line(MARGIN, y, PAGE_W - MARGIN, y, gray = 0.75f)
            y += 12
        }

        private fun patientBlock() {
            val rows =
                listOfNotNull(
                    Labels.FULL_NAME to form.fullName,
                    Labels.PATIENT_ID to patientId,
                    Labels.DATE to day(form.date.toString()),
                    form.age?.let { Labels.AGE to "$it yosh" },
                    form.gender?.let { Labels.GENDER to it },
                    form.address?.let { Labels.ADDRESS to it },
                    form.job?.let { Labels.JOB to it },
                    form.doctorName?.let { Labels.DOCTOR to it },
                )
            val valueW = contentW - 2 * BLOCK_PAD - LABEL_W
            val heights = rows.map { (_, v) -> maxOf(measure(v, 11f, false, valueW), 14f) + ROW_GAP }
            val total = heights.sum() + 2 * BLOCK_PAD - ROW_GAP
            need(total)
            ops += PdfOp.Rect(MARGIN, y, contentW, total, stroke = 0.4f, strokeWidth = 1f)
            var rowY = y + BLOCK_PAD
            rows.forEachIndexed { i, (label, value) ->
                ops += PdfOp.Text(MARGIN + BLOCK_PAD, rowY, LABEL_W, label, 11f, bold = true)
                ops += PdfOp.Text(MARGIN + BLOCK_PAD + LABEL_W, rowY, valueW, value, 11f)
                rowY += heights[i]
            }
            y += total + 2
        }

        private fun section(
            title: String,
            text: String?,
        ) {
            sectionHeader(title)
            text?.trim()?.takeIf(String::isNotEmpty)?.split('\n')?.forEach { paragraph(it) }
        }

        private fun dentalChart() {
            val d = DentalChartLayout.dentition(chart)
            val perQuad = DentalChartLayout.teethPerQuadrant(d)
            val cells = DentalChartLayout.cellsPerRow(d)
            val rowW = cells * BOX + 2 * GAP + DIVIDER
            val x0 = MARGIN + (contentW - rowW) / 2

            fun cellX(i: Int) =
                if (i < perQuad) x0 + i * BOX else x0 + perQuad * BOX + 2 * GAP + DIVIDER + (i - perQuad) * BOX

            fun cellRow(values: List<String>) {
                need(ROW)
                values.take(cells).forEachIndexed { i, v ->
                    ops += PdfOp.Rect(cellX(i), y, BOX, BOX, stroke = 0.4f)
                    if (v.isNotBlank()) ops += PdfOp.Text(cellX(i), y + 7, BOX, v.trim(), 9f, align = PdfAlign.Center)
                }
                ops += PdfOp.Rect(x0 + perQuad * BOX + GAP, y, DIVIDER, BOX, fill = 0.75f)
                y += ROW
            }

            fun note(text: String) {
                if (text.isBlank()) return
                need(NOTE)
                ops += PdfOp.Text(x0, y, rowW, text, 7f, gray = 0.4f)
                y += NOTE
            }

            fun numbers(teeth: List<String>) {
                need(NUMBERS)
                teeth.forEachIndexed { i, n ->
                    ops += PdfOp.Text(cellX(i), y + 5, BOX, n, 10f, bold = true, align = PdfAlign.Center)
                }
                y += NUMBERS
            }

            val primary = d == Dentition.Primary
            val upper =
                if (primary) DentalChartLayout.primaryUpperRight + DentalChartLayout.primaryUpperLeft
                else DentalChartLayout.upperRight + DentalChartLayout.upperLeft
            val lower =
                if (primary) DentalChartLayout.primaryLowerRight + DentalChartLayout.primaryLowerLeft
                else DentalChartLayout.lowerRight + DentalChartLayout.lowerLeft
            val jaws = listOf(DentalChartLayout.TOP, DentalChartLayout.BOTTOM)
            val rows = chart.keys.any { it.startsWith("${DentalChartLayout.TOP}_") || it.startsWith("${DentalChartLayout.BOTTOM}_") }
            // The whole chart on one page when it fits one, so its rows stay with the tooth numbers.
            val total =
                if (rows) {
                    jaws.sumOf { jaw ->
                        DentalChartLayout.visibleHistoryRows(chart, jaw)
                            .sumOf { (ROW + if (caption(it).isBlank()) 0f else NOTE).toDouble() } +
                            DentalChartLayout.editableRows(chart, jaw).size * ROW.toDouble()
                    }.toFloat() + NUMBERS
                } else {
                    2 * ROW + 2 * NUMBERS
                }
            if (total + 14 <= bottom - MARGIN) need(total + 14)
            y += 6
            if (rows) {
                // History rows (dated, signed) then this visit's rows, above the upper numbers and below them.
                jaws.forEach { jaw ->
                    if (jaw == DentalChartLayout.BOTTOM) numbers(upper)
                    DentalChartLayout.visibleHistoryRows(chart, jaw).forEach { h ->
                        val caption = caption(h)
                        // A history row and its date and doctor stay on one page.
                        need(ROW + if (caption.isBlank()) 0f else NOTE)
                        cellRow(h.cells)
                        note(caption)
                    }
                    DentalChartLayout.editableRows(chart, jaw).forEach(::cellRow)
                }
            } else {
                // A chart of single tooth values (older forms): one row per jaw over its numbers.
                fun teeth(
                    right: String,
                    left: String,
                ) = (perQuad downTo 1).map { DentalChartLayout.toothValue(chart, right, it, d) } +
                    (1..perQuad).map { DentalChartLayout.toothValue(chart, left, it, d) }
                cellRow(teeth("UR", "UL"))
                numbers(upper)
                cellRow(teeth("LR", "LL"))
                numbers(lower)
            }
            y += 8
        }

        private fun followups(followups: List<Map<String, String>>) {
            val dateW = 80f
            val doctorW = 170f
            val columns = listOf(dateW, contentW - dateW - doctorW, doctorW)

            fun height(
                values: List<String>,
                header: Boolean,
            ) = values.zip(columns).maxOf { (v, w) -> measure(v, 9f, header, w - 8) } + 8

            fun draw(
                values: List<String>,
                header: Boolean,
                h: Float,
            ) {
                var x = MARGIN
                values.zip(columns).forEach { (v, w) ->
                    ops += PdfOp.Rect(x, y, w, h, fill = if (header) 0.93f else null, stroke = 0.4f)
                    ops += PdfOp.Text(x + 4, y + 4, w - 8, v, 9f, bold = header)
                    x += w
                }
                y += h
            }

            // A row taller than the room left goes to a fresh page; one taller than a page is split between the
            // findings' words, its date and doctor on the first part (nothing is cut off). Kept as the app wrote it.
            @Suppress("LoopWithTooManyJumpStatements")
            fun row(
                values: List<String>,
                header: Boolean,
            ) {
                var rest = values
                while (true) {
                    val h = height(rest, header)
                    if (y + h <= bottom) return draw(rest, header, h)
                    if (y > MARGIN && h <= bottom - MARGIN) {
                        newPage()
                        continue
                    }
                    val words = rest[1].split(' ')
                    val fit = fitWords(words, 9f, header, columns[1] - 8, bottom - y - 8)
                    if (fit == 0) {
                        if (y > MARGIN) {
                            newPage()
                            continue
                        }
                        return draw(rest, header, h)
                    }
                    draw(listOf(rest[0], words.take(fit).joinToString(" "), rest[2]), header, bottom - y)
                    newPage()
                    rest = listOf("", words.drop(fit).joinToString(" "), "")
                }
            }
            y += 6
            row(listOf(Labels.DATE, Labels.FINDINGS, Labels.DOCTOR_SURNAME), header = true)
            followups.forEach { f ->
                row(listOf(day(f["date"].orEmpty()), f["clinicalFindings"].orEmpty(), f["doctorName"].orEmpty()), header = false)
            }
        }

        private fun signature() {
            val raw = form.patientSignatureImage?.trim()?.takeIf(String::isNotEmpty) ?: return
            val bytes =
                runCatching { Base64.getMimeDecoder().decode(raw.substringAfter(",", raw).trim()) }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() } ?: return
            y += 16
            sectionHeader(Labels.SIGNATURE)
            need(72f + 8 + 20)
            y += 8
            ops += PdfOp.Image(MARGIN, y, contentW, 72f, bytes)
            y += 72f + 8
            form.patientSignedAt?.let {
                ops += PdfOp.Text(MARGIN, y, contentW, "${Labels.DATE}: ${day(it.toString())}", 10f)
                y += 14
            }
        }
    }

    /** The official 025-2's Uzbek labels, kept in Uzbek in every language. */
    object Labels {
        const val TITLE = "STOMATOLOGIK BEMORNING TIBBIY KARTASI"
        const val ORDER = "2017-yil 25-dekabrdagi Nr. 777-sonli buyruq bilan tasdiqlangan 025-2 raqamli tibbiy hujjat shakli"
        const val FULL_NAME = "To'liq ism"
        const val PATIENT_ID = "Bemor ID"
        const val DATE = "Sana"
        const val AGE = "Yoshi"
        const val GENDER = "Jinsi"
        const val ADDRESS = "Manzil"
        const val JOB = "Kasbi"
        const val DIAGNOSIS = "TASHXIS"
        const val DOCTOR = "Shifokor"
        const val COMPLAINTS = "SHIKOYATI"
        const val OBJECTIVE_EXAM = "OBYEKTIV TEKSHIRUV"
        const val PAST_AND_COMORBID = "BOSHDAN O'TKAZGAN VA YO'LDOSH KASALLIKLAR"
        const val OCCLUSION = "TISH JIPSLANISHI (PRIPKUS)"
        const val DISEASE_DEVELOPMENT = "KASALLIK RIVOJLANISHI"
        const val ORAL_CAVITY = "OG'IZ BO'SHLIG'I HOLATI"
        const val XRAY_LAB = "RENTGEN VA LABORATOR TEKSHIRUV MA'LUMOTLARI"
        const val DENTAL_CHART = "TISH DIAGRAMMASI"
        const val TREATMENT = "DAVOLANISH"
        const val TREATMENT_RESULT = "DAVOLANISH NATIJASI (EPIKRIZ)"
        const val RECOMMENDATIONS = "KO'RSATMALAR"
        const val RETURN_VISITS = "QAYTA TASHRIFLAR"
        const val SIGNATURE = "BEMOR IMZOSI"
        const val FINDINGS = "Qayta kasallanib murojaat qilgan bemor anamnezi, statusi, tashxisi va davolanish kundaligi"
        const val DOCTOR_SURNAME = "Davolovchi shifokor familiyasi"
    }
}
