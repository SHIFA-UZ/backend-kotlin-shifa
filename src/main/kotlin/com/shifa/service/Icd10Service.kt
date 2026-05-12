package com.shifa.service

import com.shifa.config.CacheConfig
import com.shifa.repo.Icd10CodeRepository
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.text.Normalizer

data class Icd10SearchResult(
    val code: String,
    val title: String,
    val titleRu: String? = null,
    val titleUz: String? = null,
    val subtitle: String? = null
)

@Service
class Icd10Service(
    private val repo: Icd10CodeRepository
) {
    // Common multilingual medical aliases for ICD lookup (EN/RU/UZ transliteration).
    // This keeps search practical for doctors typing in Uzbek/Russian keyboard habits.
    private val queryAliases: Map<String, List<String>> = mapOf(
        "кариес" to listOf("caries", "kariyes"),
        "kariyes" to listOf("caries", "кариес"),
        "caries" to listOf("кариес", "kariyes"),
        "пульпит" to listOf("pulpitis"),
        "pulpit" to listOf("pulpitis", "пульпит"),
        "pulpitis" to listOf("пульпит", "pulpit"),
        "периодонтит" to listOf("periodontitis"),
        "periodontit" to listOf("periodontitis", "периодонтит"),
        "periodontitis" to listOf("периодонтит", "periodontit"),
        "гингивит" to listOf("gingivitis"),
        "gingivit" to listOf("gingivitis", "гингивит"),
        "gingivitis" to listOf("гингивит", "gingivit"),
        "стоматит" to listOf("stomatitis"),
        "stomatit" to listOf("stomatitis", "стоматит"),
        "челюсть" to listOf("jaw"),
        "jag'" to listOf("jaw"),
        "tish" to listOf("tooth", "teeth"),
        "zub" to listOf("tooth", "teeth"),
        "зуб" to listOf("tooth", "teeth"),
        "milk tooth" to listOf("primary tooth"),
        "sut tishi" to listOf("primary tooth"),
        "внчс" to listOf("tmj"),
        "tmj" to listOf("внчс"),
        "migren" to listOf("migraine", "мигрень"),
        "migraine" to listOf("migren", "мигрень"),
        "гипертония" to listOf("hypertension"),
        "gipertoniya" to listOf("hypertension", "гипертония"),
        "диабет" to listOf("diabetes"),
        "diabet" to listOf("diabetes", "диабет")
    )

    private fun normalizeQuery(qRaw: String): String {
        val trimmed = qRaw.trim()
        if (trimmed.isEmpty()) return ""
        val noPunct = trimmed.replace(Regex("[\\p{Punct}]"), " ")
        val collapsed = noPunct.replace(Regex("\\s+"), " ").trim()
        // NFKC helps normalize Unicode variants safely.
        return Normalizer.normalize(collapsed.lowercase(), Normalizer.Form.NFKC)
    }

    private fun expandQuery(qNorm: String): String {
        if (qNorm.isBlank()) return qNorm
        val tokens = qNorm.split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return qNorm

        val expanded = LinkedHashSet<String>()
        expanded.add(qNorm)
        for (token in tokens) {
            expanded.add(token)
            queryAliases[token]?.forEach { expanded.add(it) }
        }
        return expanded.joinToString(" ").trim()
    }

    private fun toTsQuery(qNorm: String): String {
        // websearch_to_tsquery handles spacing/quotes; keep it conservative by trimming.
        return qNorm.take(120)
    }

    @Cacheable(cacheNames = [CacheConfig.ICD10_SEARCH_CACHE], key = "#qRaw + ':' + #limit")
    fun searchByQuery(qRaw: String, limit: Int = 20): List<Icd10SearchResult> {
        val q = qRaw.trim()
        if (q.isEmpty()) return emptyList()
        val safeLimit = limit.coerceIn(1, 20) // hard limit for performance

        val qNorm = normalizeQuery(q)
        if (qNorm.isEmpty()) return emptyList()
        val qExpanded = expandQuery(qNorm)

        // Fuzzy matching is optional but safe; keep threshold moderate.
        val simThreshold = if (qExpanded.length <= 3) 0.6 else 0.35

        val rows = repo.searchRankedNative(
            qRaw = qExpanded,
            qCore = qNorm,
            qTs = toTsQuery(qExpanded),
            simThreshold = simThreshold,
            limit = safeLimit
        )

        return rows.map { r ->
            // Subtitle is future-ready: show parent code if present, otherwise show RU title if distinct.
            val subtitle = r.getParentcode()?.takeIf { it.isNotBlank() }
                ?: r.getTitleru()?.takeIf { it.isNotBlank() && it != r.getTitle() }
            Icd10SearchResult(
                code = r.getCode(),
                title = r.getTitle(),
                titleRu = r.getTitleru(),
                titleUz = r.getTitleuz(),
                subtitle = subtitle
            )
        }
    }
}

