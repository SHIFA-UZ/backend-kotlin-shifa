package com.shifa.clinical

/**
 * Expands form 025-2 [dental_chart] JSON maps into plain-language lines tied to **ISO 3950 / FDI** tooth codes,
 * mirroring the doctor app's [DentalChartCodec] / PDF layout so phrases like "tooth 22" align with stored cells.
 *
 * Supports:
 * - FDI keys ("11"…"48" permanent; "51"…"85" primary)
 * - Legacy per-tooth keys ("UR1"…"LL8")
 * - UI grid keys (`TOP_row_cell`, `BOTTOM_row_cell`) and history rows (`TOP_HIST_*`)
 */
object DentalChartExpansion {

    private val legacyToothKey = Regex("^(UR|UL|LR|LL)([1-8])$")
    private val fdiKeyPattern = Regex("^[1-8][1-8]$")
    private val editableRowKey = Regex("^(TOP|BOTTOM)_(\\d+)_(\\d+)$")
    private val histCellKey = Regex("^(TOP|BOTTOM)_HIST_(\\d+)_(\\d+)$")
    private val histMetaKey = Regex("^(TOP|BOTTOM)_HIST_(\\d+)_(date|doctor)$")

    private val upperRightFdi = listOf("18", "17", "16", "15", "14", "13", "12", "11")
    private val upperLeftFdi = listOf("21", "22", "23", "24", "25", "26", "27", "28")
    private val lowerRightFdi = listOf("48", "47", "46", "45", "44", "43", "42", "41")
    private val lowerLeftFdi = listOf("31", "32", "33", "34", "35", "36", "37", "38")

    private const val cellsPerJawRow = 16

    fun migrateLegacyToothKeys(source: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in source) {
            val m = legacyToothKey.matchEntire(k)
            if (m == null) {
                out[k] = v
                continue
            }
            val quad = m.groupValues[1]
            val n = m.groupValues[2].toIntOrNull() ?: continue
            if (n !in 1..8) continue
            val fdi = fdiKey(quad, n)
            out.putIfAbsent(fdi, v)
        }
        return out
    }

    /** One readable line per non-empty tooth-specific cell (sorted by numeric FDI where possible). */
    fun expandDentalChartToLines(rawChart: Map<String, String>?): List<String> {
        val chart = migrateLegacyToothKeys(rawChart ?: emptyMap())
        val lines = LinkedHashMap<String, String>()

        fun putLine(sortKey: String, display: String) {
            lines.putIfAbsent(sortKey, display)
        }

        // Direct FDI + leftover legacy keys that survived migration under nonstandard shapes
        for ((k, v) in chart) {
            if (v.isBlank()) continue
            val compact = k.trim()
            when {
                fdiKeyPattern.matches(compact) ->
                    putLine("fdi_${compact.padStart(2, '0')}", formatToothLine(compact, v.trim()))
                legacyToothKey.matches(compact) -> {
                    val m = legacyToothKey.matchEntire(compact)!!
                    val fdi = fdiKey(m.groupValues[1], m.groupValues[2].toInt())
                    putLine("fdi_$fdi", formatToothLine(fdi, v.trim()))
                }
            }
        }

        // TOP / BOTTOM grid + history (same column ordering as Flutter PDF)
        appendJawGrid(chart, "TOP", isUpper = true) { sk, disp -> putLine(sk, disp) }
        appendJawGrid(chart, "BOTTOM", isUpper = false) { sk, disp -> putLine(sk, disp) }

        return lines.entries
            .sortedWith(compareBy({ sortKeyToothOrdinal(it.key) }, { it.key }))
            .map { it.value }
    }

    /** Appointment dental-documentation payload uses compact UR8-style keys — map each to FDI for indexing. */
    fun expandAppointmentTeethMap(teeth: Map<String, Any?>): List<String> {
        val out = LinkedHashMap<String, String>()
        for ((key, value) in teeth) {
            val compact = key.trim().replace(" ", "")
            val m = legacyToothKey.matchEntire(compact) ?: continue
            val fdi = fdiKey(m.groupValues[1], m.groupValues[2].toInt())
            val text = stringifyAppointmentToothValue(value).trim()
            if (text.isEmpty()) continue
            out.putIfAbsent(
                "fdi_$fdi",
                "${formatToothLine(fdi, text)} (appointment visit documentation)"
            )
        }
        return out.entries.sortedWith(compareBy({ sortKeyToothOrdinal(it.key) }, { it.key })).map { it.value }
    }

    private fun stringifyAppointmentToothValue(value: Any?): String {
        return when (value) {
            null -> ""
            is String -> value
            is Iterable<*> -> value.joinToString("; ") { stringifyAppointmentToothValue(it) }
            is Map<*, *> -> {
                val title = (value["title"] ?: value["name"])?.toString()?.trim().orEmpty()
                val qty = value["qty"] ?: value["quantity"]
                val parts = mutableListOf<String>()
                if (title.isNotEmpty()) parts += title
                if (qty != null) parts += "qty=$qty"
                parts.joinToString(" ").ifEmpty { value.toString() }
            }
            else -> value.toString()
        }
    }

    private fun appendJawGrid(
        chart: Map<String, String>,
        jawPrefix: String,
        isUpper: Boolean,
        putLine: (sortKey: String, display: String) -> Unit,
    ) {
        val histRows = parseHistRows(chart, jawPrefix)
        for (h in histRows) {
            val note = histMetaNote(h.dateIso, h.doctor)
            for (ci in 0 until cellsPerJawRow) {
                val cellVal = h.cells.getOrNull(ci)?.trim().orEmpty()
                if (cellVal.isEmpty()) continue
                val toothNum = (if (isUpper) fdiForUpperCell(ci) else fdiForLowerCell(ci)) ?: continue
                val suffix = if (note.isEmpty()) " [past chart snapshot]" else " [past chart snapshot $note]"
                putLine(
                    "fdi_${toothNum}_hist_${h.sortIndex}_$ci",
                    formatToothLine(toothNum, cellVal) + suffix
                )
            }
        }

        val editable = parseEditableRows(chart, jawPrefix)
        for ((ri, row) in editable.withIndex()) {
            for (ci in 0 until cellsPerJawRow) {
                val cellVal = row.getOrNull(ci)?.trim().orEmpty()
                if (cellVal.isEmpty()) continue
                val toothNum = (if (isUpper) fdiForUpperCell(ci) else fdiForLowerCell(ci)) ?: continue
                putLine(
                    "fdi_${toothNum}_edit_${ri}_$ci",
                    formatToothLine(toothNum, cellVal) + " [current diagram row ${ri + 1}]"
                )
            }
        }
    }

    private fun formatToothLine(fdi: String, value: String): String =
        "ISO3950 tooth $fdi (also spoken as FDI $fdi): $value"

    private fun fdiForUpperCell(cellIndex: Int): String? = when (cellIndex) {
        in 0..7 -> upperRightFdi.getOrNull(cellIndex)
        in 8..15 -> upperLeftFdi.getOrNull(cellIndex - 8)
        else -> null
    }

    private fun fdiForLowerCell(cellIndex: Int): String? = when (cellIndex) {
        in 0..7 -> lowerRightFdi.getOrNull(cellIndex)
        in 8..15 -> lowerLeftFdi.getOrNull(cellIndex - 8)
        else -> null
    }

    private fun parseEditableRows(chart: Map<String, String>, jaw: String): List<List<String>> {
        val rowMap = mutableMapOf<Int, MutableList<String>>()
        val prefix = "${jaw}_"
        for ((key, value) in chart) {
            if (!key.startsWith(prefix)) continue
            if (key.startsWith("${jaw}_HIST")) continue
            val m = editableRowKey.matchEntire(key) ?: continue
            if (m.groupValues[1] != jaw) continue
            val rowIndex = m.groupValues[2].toIntOrNull() ?: continue
            val cellIndex = m.groupValues[3].toIntOrNull() ?: continue
            val row = rowMap.getOrPut(rowIndex) {
                MutableList(cellsPerJawRow) { "" }
            }
            if (cellIndex in 0 until cellsPerJawRow) {
                row[cellIndex] = value
            }
        }
        if (rowMap.isEmpty()) return emptyList()
        return rowMap.keys.sorted().map { idx ->
            rowMap[idx]!!.toList()
        }
    }

    private data class HistRow(
        val sortIndex: Int,
        val cells: List<String>,
        val dateIso: String?,
        val doctor: String?,
    )

    private fun parseHistRows(chart: Map<String, String>, jaw: String): List<HistRow> {
        val rowCells = mutableMapOf<Int, MutableMap<Int, String>>()
        val dates = mutableMapOf<Int, String>()
        val doctors = mutableMapOf<Int, String>()
        val prefix = "${jaw}_HIST_"

        for ((key, value) in chart) {
            if (!key.startsWith(prefix)) continue
            histMetaKey.matchEntire(key)?.let { m ->
                if (m.groupValues[1] != jaw) return@let
                val ri = m.groupValues[2].toIntOrNull() ?: return@let
                when (m.groupValues[3]) {
                    "date" -> dates[ri] = value
                    "doctor" -> doctors[ri] = value
                }
                return@let
            }
            val cell = histCellKey.matchEntire(key) ?: continue
            if (cell.groupValues[1] != jaw) continue
            val ri = cell.groupValues[2].toIntOrNull() ?: continue
            val ci = cell.groupValues[3].toIntOrNull() ?: continue
            if (ci !in 0 until cellsPerJawRow) continue
            rowCells.getOrPut(ri) { mutableMapOf() }[ci] = value
        }

        return rowCells.keys.sorted().map { ri ->
            val sparse = rowCells[ri] ?: emptyMap()
            val cells = (0 until cellsPerJawRow).map { i -> sparse[i].orEmpty() }
            HistRow(
                sortIndex = ri,
                cells = cells,
                dateIso = dates[ri],
                doctor = doctors[ri],
            )
        }
    }

    private fun histMetaNote(dateIso: String?, doctor: String?): String {
        val d = dateIso?.trim().orEmpty()
        val doc = doctor?.trim().orEmpty()
        return when {
            d.isEmpty() && doc.isEmpty() -> ""
            d.isEmpty() -> doc
            doc.isEmpty() -> d
            else -> "$d · $doc"
        }
    }

    private fun quadrantDigit(q: String): Int = when (q) {
        "UR" -> 1
        "UL" -> 2
        "LL" -> 3
        "LR" -> 4
        else -> 0
    }

    private fun fdiKey(quadrant: String, toothNum: Int): String =
        "${quadrantDigit(quadrant)}$toothNum"

    private fun sortKeyToothOrdinal(key: String): Int {
        val m = Regex("^fdi_(\\d{2})").find(key) ?: return Int.MAX_VALUE
        return m.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE
    }
}
