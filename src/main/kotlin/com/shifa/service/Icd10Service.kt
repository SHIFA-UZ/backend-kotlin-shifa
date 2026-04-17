package com.shifa.service

import com.shifa.config.CacheConfig
import com.shifa.repo.Icd10CodeRepository
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.text.Normalizer

data class Icd10SearchResult(
    val code: String,
    val title: String,
    val subtitle: String? = null
)

@Service
class Icd10Service(
    private val repo: Icd10CodeRepository
) {
    private fun normalizeQuery(qRaw: String): String {
        val trimmed = qRaw.trim()
        if (trimmed.isEmpty()) return ""
        val noPunct = trimmed.replace(Regex("[\\p{Punct}]"), " ")
        val collapsed = noPunct.replace(Regex("\\s+"), " ").trim()
        // NFKC helps normalize Unicode variants safely.
        return Normalizer.normalize(collapsed.lowercase(), Normalizer.Form.NFKC)
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

        // Fuzzy matching is optional but safe; keep threshold moderate.
        val simThreshold = if (qNorm.length <= 3) 0.6 else 0.35

        val rows = repo.searchRankedNative(
            qRaw = q,
            qNorm = qNorm,
            qTs = toTsQuery(qNorm),
            simThreshold = simThreshold,
            limit = safeLimit
        )

        return rows.map { r ->
            // Subtitle is future-ready: show parent code if present, otherwise show RU title if distinct.
            val subtitle = r.getParentcode()?.takeIf { it.isNotBlank() }
                ?: r.getTitleru()?.takeIf { it.isNotBlank() && it != r.getTitle() }
            Icd10SearchResult(code = r.getCode(), title = r.getTitle(), subtitle = subtitle)
        }
    }
}

