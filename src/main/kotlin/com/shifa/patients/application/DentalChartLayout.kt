package com.shifa.patients.application

/** Adult or child teeth on a dental chart. */
internal enum class Dentition { Permanent, Primary }

/** A read-only history row of a 025-2 dental chart: its cells, the visit's date and doctor, the teeth it is for. */
internal data class DentalHistoryRow(
    val index: Int,
    val cells: List<String>,
    val date: String? = null,
    val doctor: String? = null,
    val dentition: Dentition? = null,
)

/**
 * What the 025-2 PDF reads from a stored dental chart (ported from the doctor app's `data/forms/DentalChartCodec.kt`,
 * the read side only), on the flat map the form stores:
 * - teeth in FDI keys ("11".."48", "51".."85"), legacy "UR1".."LL8";
 * - editable rows `TOP_{row}_{cell}`, `BOTTOM_{row}_{cell}` (16 cells, 10 for primary teeth);
 * - history rows `TOP_HIST_{i}_{cell}` with `_date`, `_doctor`, `_dentition` (and BOTTOM);
 * - `__dentition__` = `permanent` | `primary`.
 */
internal object DentalChartLayout {
    const val DENTITION_KEY = "__dentition__"
    const val TOP = "TOP"
    const val BOTTOM = "BOTTOM"

    val upperRight = listOf("18", "17", "16", "15", "14", "13", "12", "11")
    val upperLeft = listOf("21", "22", "23", "24", "25", "26", "27", "28")
    val lowerRight = listOf("48", "47", "46", "45", "44", "43", "42", "41")
    val lowerLeft = listOf("31", "32", "33", "34", "35", "36", "37", "38")
    val primaryUpperRight = listOf("55", "54", "53", "52", "51")
    val primaryUpperLeft = listOf("61", "62", "63", "64", "65")
    val primaryLowerRight = listOf("85", "84", "83", "82", "81")
    val primaryLowerLeft = listOf("71", "72", "73", "74", "75")

    private val editableCell = Regex("^(TOP|BOTTOM)_(\\d+)_(\\d+)$")
    private val historyCell = Regex("^(TOP|BOTTOM)_HIST_(\\d+)_(\\d+)$")
    private val historyMeta = Regex("^(TOP|BOTTOM)_HIST_(\\d+)_(date|doctor|dentition)$")

    fun cellsPerRow(d: Dentition) = if (d == Dentition.Primary) 10 else 16

    fun teethPerQuadrant(d: Dentition) = if (d == Dentition.Primary) 5 else 8

    fun dentition(chart: Map<String, String>): Dentition = dentitionOf(chart[DENTITION_KEY])

    private fun dentitionOf(raw: String?) =
        if (raw?.trim()?.lowercase() == "primary") Dentition.Primary else Dentition.Permanent

    /** "UR" → 1 (5 for primary), "UL" → 2, "LL" → 3, "LR" → 4; 0 for anything else. */
    private fun quadrantDigit(
        quadrant: String,
        d: Dentition,
    ): Int {
        val base =
            when (quadrant) {
                "UR" -> 1
                "UL" -> 2
                "LL" -> 3
                "LR" -> 4
                else -> return 0
            }
        return if (d == Dentition.Primary) base + 4 else base
    }

    /** A tooth's value: its FDI key first, then the legacy one. */
    fun toothValue(
        chart: Map<String, String>,
        quadrant: String,
        tooth: Int,
        d: Dentition,
    ): String = chart["${quadrantDigit(quadrant, d)}$tooth"] ?: chart["$quadrant$tooth"].orEmpty()

    /** The editable rows of [jaw] (`TOP` / `BOTTOM`), in row order, each [cellsPerRow] wide. */
    fun editableRows(
        chart: Map<String, String>,
        jaw: String,
    ): List<List<String>> {
        val cells = cellsPerRow(dentition(chart))
        val rows = mutableMapOf<Int, MutableList<String>>()
        chart.forEach { (k, v) ->
            val m = editableCell.find(k) ?: return@forEach
            if (m.groupValues[1] != jaw) return@forEach
            val row = rows.getOrPut(m.groupValues[2].toInt()) { MutableList(cells) { "" } }
            m.groupValues[3].toInt().takeIf { it < cells }?.let { row[it] = v }
        }
        return rows.entries.sortedBy { it.key }.map { it.value.toList() }
    }

    /** The read-only history rows of [jaw], in order, with their visit's date, doctor and teeth. */
    private fun historyRows(
        chart: Map<String, String>,
        jaw: String,
    ): List<DentalHistoryRow> {
        val rows = mutableMapOf<Int, MutableMap<Int, String>>()
        val dates = mutableMapOf<Int, String>()
        val doctors = mutableMapOf<Int, String>()
        val teeth = mutableMapOf<Int, Dentition>()
        chart.forEach { (k, v) ->
            historyMeta.find(k)?.takeIf { it.groupValues[1] == jaw }?.let { m ->
                val i = m.groupValues[2].toInt()
                when (m.groupValues[3]) {
                    "date" -> dates[i] = v
                    "doctor" -> doctors[i] = v
                    else -> teeth[i] = dentitionOf(v)
                }
                return@forEach
            }
            historyCell.find(k)?.takeIf { it.groupValues[1] == jaw }?.let { m ->
                rows.getOrPut(m.groupValues[2].toInt()) { mutableMapOf() }[m.groupValues[3].toInt()] = v
            }
        }
        val current = dentition(chart)
        return rows.entries.sortedBy { it.key }.map { (i, sparse) ->
            DentalHistoryRow(i, List(cellsPerRow(teeth[i] ?: current)) { sparse[it].orEmpty() }, dates[i], doctors[i], teeth[i])
        }
    }

    /** The history rows that line up with the chart's teeth (a row recorded for the other dentition is left out). */
    fun visibleHistoryRows(
        chart: Map<String, String>,
        jaw: String,
    ): List<DentalHistoryRow> =
        dentition(chart).let { d -> historyRows(chart, jaw).filter { it.dentition == null || it.dentition == d } }

    /** A history date "2026-03-12" as "12.03.2026"; anything else as written. */
    fun displayHistoryDate(iso: String?): String {
        if (iso.isNullOrEmpty()) return ""
        val parts = iso.split('-')
        return if (parts.size == 3) "${parts[2].padStart(2, '0')}.${parts[1].padStart(2, '0')}.${parts[0]}" else iso
    }
}
