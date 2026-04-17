package com.shifa.service

import com.shifa.repo.Icd10CodeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.math.max
import kotlin.math.min

data class IcdSuggestion(
    val code: String,
    val title: String,
    val confidence: Double? = null,
    val isTop: Boolean = false
)

@Service
class DiagnosisSuggestionService(
    private val openAi: OpenAiResponsesService,
    private val icdRepo: Icd10CodeRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun suggestFromText(textRaw: String): List<IcdSuggestion> {
        val text = textRaw.trim()
        if (text.isEmpty()) return emptyList()

        val terms = try {
            openAi.extractDiagnosisTerms(text)
        } catch (e: Exception) {
            // Suggestions must never block workflows.
            log.warn("ICD suggestion: term extraction failed: {}", e.message)
            return emptyList()
        }

        if (terms.isEmpty()) return emptyList()

        data class Acc(
            val code: String,
            val title: String,
            val category: String,
            var score: Double,
            var supportTerms: Int
        )

        fun normalizeQuery(qRaw: String): String {
            val trimmed = qRaw.trim()
            if (trimmed.isEmpty()) return ""
            val noPunct = trimmed.replace(Regex("[\\p{Punct}]"), " ")
            return noPunct.replace(Regex("\\s+"), " ").trim().lowercase()
        }

        fun categoryOf(code: String): String {
            // Dedup category-level duplicates: J06.9 and J06 -> category "J06"
            val base = code.trim()
            val dot = base.indexOf('.')
            return if (dot > 0) base.substring(0, dot) else base
        }

        fun specificity(code: String): Int {
            // Prefer more specific codes: has dot and longer length.
            val c = code.trim()
            val hasDot = if (c.contains('.')) 2 else 0
            return hasDot + c.length
        }

        // Aggregate best matches across all extracted terms.
        val accByCode = LinkedHashMap<String, Acc>()
        for (term in terms.take(5)) {
            val qRaw = term.take(80)
            val qNorm = normalizeQuery(qRaw)
            if (qNorm.isBlank()) continue

            val rows = try {
                icdRepo.searchRankedNative(
                    qRaw = qRaw,
                    qNorm = qNorm,
                    qTs = qNorm.take(120),
                    simThreshold = if (qNorm.length <= 3) 0.6 else 0.35,
                    limit = 20
                )
            } catch (e: Exception) {
                log.warn("ICD suggestion: search failed for term='{}': {}", qRaw, e.message)
                emptyList()
            }

            // Use top N rows; score is already a mixed signal.
            for ((idx, r) in rows.take(10).withIndex()) {
                val code = r.getCode()
                val title = r.getTitle()
                val baseScore = r.getScore()
                // Slightly discount deeper ranks per-term.
                val rankFactor = 1.0 - (idx * 0.04)
                val weighted = baseScore * max(0.6, rankFactor)

                val existing = accByCode[code]
                if (existing == null) {
                    accByCode[code] = Acc(
                        code = code,
                        title = title,
                        category = categoryOf(code),
                        score = weighted,
                        supportTerms = 1
                    )
                } else {
                    existing.score += weighted * 0.55
                    existing.supportTerms += 1
                }
            }
        }

        if (accByCode.isEmpty()) return emptyList()

        // Category-level dedup: keep best per category first, but allow multiple if strong and very specific.
        val sorted = accByCode.values
            .sortedWith(
                compareByDescending<Acc> { it.score }
                    .thenByDescending { it.supportTerms }
                    .thenByDescending { specificity(it.code) }
            )

        val chosen = ArrayList<Acc>(5)
        val seenCategories = HashSet<String>()
        for (a in sorted) {
            if (chosen.size >= 5) break
            val catSeen = a.category in seenCategories
            if (!catSeen) {
                chosen.add(a)
                seenCategories.add(a.category)
                continue
            }
            // If same category already selected, only keep if much more specific and strong score.
            val bestInCat = chosen.firstOrNull { it.category == a.category }
            if (bestInCat != null) {
                val specDiff = specificity(a.code) - specificity(bestInCat.code)
                if (specDiff >= 2 && a.score >= bestInCat.score * 0.9) {
                    chosen.add(a)
                }
            }
        }

        if (chosen.isEmpty()) return emptyList()

        // Dynamic confidence based on relative score, specificity, and support terms.
        val maxScore = chosen.maxOf { it.score }.takeIf { it > 0 } ?: 1.0
        val maxSupport = chosen.maxOf { it.supportTerms }.coerceAtLeast(1)

        fun confidence(a: Acc): Double {
            val rel = (a.score / maxScore).coerceIn(0.0, 1.0)
            val support = (a.supportTerms.toDouble() / maxSupport.toDouble()).coerceIn(0.0, 1.0)
            val spec = min(1.0, specificity(a.code).toDouble() / 10.0)
            // Weighted: score dominates, but support/spec improve stability.
            return (rel * 0.65 + support * 0.20 + spec * 0.15).coerceIn(0.05, 0.98)
        }

        val withConfidence = chosen
            .map { a -> a to confidence(a) }
            .sortedWith(
                compareByDescending<Pair<Acc, Double>> { it.second }
                    .thenByDescending { specificity(it.first.code) }
                    .thenByDescending { it.first.supportTerms }
            )

        val top = withConfidence.firstOrNull()?.first
        return withConfidence.map { (a, conf) ->
            IcdSuggestion(
                code = a.code,
                title = a.title,
                confidence = conf,
                isTop = (top != null && a.code == top.code)
            )
        }
    }
}

