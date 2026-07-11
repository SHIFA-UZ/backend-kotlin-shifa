package com.shifa.clinicalengine.service

import com.shifa.clinicalengine.repo.ClinicalDentalToothKeyRepository
import org.springframework.stereotype.Service

@Service
class DentalChartKeyValidator(
    private val toothKeys: ClinicalDentalToothKeyRepository,
) {
    private val fdiPattern = Regex("^[1-8][1-8]$")
    private val gridKeyPattern = Regex("^(TOP|BOTTOM)_(\\d+|HIST_\\d+)_(\\d+|date|doctor)$")
    private val legacyKeyPattern = Regex("^(UR|UL|LR|LL)([1-8])$")

    @Volatile
    private var allowedFdi: Set<String>? = null

    fun sanitizeDentalChart(raw: Map<String, String>?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val allowed = loadAllowedFdi()
        val out = LinkedHashMap<String, String>()
        for ((key, value) in raw) {
            if (value.isBlank()) continue
            val compact = key.trim()
            when {
                fdiPattern.matches(compact) && compact !in allowed -> continue
                fdiPattern.matches(compact) || gridKeyPattern.matches(compact) || legacyKeyPattern.matches(compact) ->
                    out[compact] = value.trim()
                else -> out[compact] = value.trim()
            }
        }
        return out
    }

    fun isValidFdiKey(key: String): Boolean = loadAllowedFdi().contains(key)

    private fun loadAllowedFdi(): Set<String> {
        allowedFdi?.let { return it }
        synchronized(this) {
            allowedFdi?.let { return it }
            val loaded = toothKeys.findAll().map { it.fdiKey }.toSet()
            allowedFdi = if (loaded.isEmpty()) defaultFdiKeys() else loaded
            return allowedFdi!!
        }
    }

    private fun defaultFdiKeys(): Set<String> = setOf(
        "18", "17", "16", "15", "14", "13", "12", "11",
        "21", "22", "23", "24", "25", "26", "27", "28",
        "48", "47", "46", "45", "44", "43", "42", "41",
        "31", "32", "33", "34", "35", "36", "37", "38",
        "55", "54", "53", "52", "51",
        "61", "62", "63", "64", "65",
        "71", "72", "73", "74", "75",
        "85", "84", "83", "82", "81",
    )
}
