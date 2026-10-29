package com.shifa.ai.infrastructure

import com.shifa.ai.application.AiRoutingProperties
import com.shifa.ai.domain.LlmPurpose
import com.shifa.ai.domain.RequestCategory

import com.shifa.ai.domain.OutputLanguage
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Persists one row per model call to `ai_llm_request_log` and emits a structured log line.
 * Also answers local budget questions (daily / monthly token sums per provider).
 *
 * This is accounting for routing decisions. It does not cap what a provider bills.
 * Inserts are best-effort: a failure is logged and never propagated to the doctor's request.
 */
@Component
class LlmUsageRecorder(
    private val jdbc: JdbcTemplate,
    private val props: AiRoutingProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger("com.shifa.ai.llm.usage")

    data class Record(
        val requestId: String,
        val doctorId: Long?,
        val providerId: String,
        val model: String,
        val language: OutputLanguage?,
        val category: RequestCategory?,
        val purpose: LlmPurpose,
        val inputTokens: Int?,
        val outputTokens: Int?,
        val durationMs: Long,
        val outcome: String,
        val fallbackIndex: Int,
        val toolNames: List<String>,
    )

    private data class BudgetSnapshot(val atMs: Long, val day: Long, val month: Long)

    private val budgetCache = ConcurrentHashMap<String, BudgetSnapshot>()

    fun record(r: Record) {
        log.info(
            "llm_call requestId={} doctorId={} provider={} model={} lang={} category={} purpose={} in={} out={} ms={} outcome={} fallback={} tools={}",
            r.requestId, r.doctorId, r.providerId, r.model, r.language?.isoCode, r.category?.name, r.purpose.name,
            r.inputTokens, r.outputTokens, r.durationMs, r.outcome, r.fallbackIndex, r.toolNames.joinToString(","),
        )
        try {
            jdbc.update(
                """
                INSERT INTO ai_llm_request_log
                  (request_id, doctor_id, provider, model, language, category, purpose,
                   input_tokens, output_tokens, duration_ms, outcome, fallback_index, tool_names)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                r.requestId, r.doctorId, r.providerId, r.model, r.language?.isoCode, r.category?.name, r.purpose.name,
                r.inputTokens, r.outputTokens, r.durationMs, r.outcome, r.fallbackIndex,
                r.toolNames.joinToString(","),
            )
        } catch (e: Exception) {
            log.warn("llm_usage_insert_failed provider={} reason={}", r.providerId, e.javaClass.simpleName)
        }
    }

    fun isOverBudget(p: LlmProviderConfig): Boolean {
        if (p.dailyTokenBudget <= 0 && p.monthlyTokenBudget <= 0) return false
        val snap = snapshot(p.id) ?: return false
        if (p.dailyTokenBudget > 0 && snap.day >= p.dailyTokenBudget) return true
        if (p.monthlyTokenBudget > 0 && snap.month >= p.monthlyTokenBudget) return true
        return false
    }

    private fun snapshot(providerId: String): BudgetSnapshot? {
        val now = clock.millis()
        budgetCache[providerId]?.let { if (now - it.atMs < props.routing.budgetCacheMs) return it }
        return try {
            val row = jdbc.queryForMap(
                """
                SELECT
                  COALESCE(SUM(CASE WHEN created_at >= date_trunc('day', now()) THEN COALESCE(input_tokens,0)+COALESCE(output_tokens,0) END),0) AS day_tokens,
                  COALESCE(SUM(CASE WHEN created_at >= date_trunc('month', now()) THEN COALESCE(input_tokens,0)+COALESCE(output_tokens,0) END),0) AS month_tokens
                FROM ai_llm_request_log
                WHERE provider = ? AND created_at >= date_trunc('month', now())
                """.trimIndent(),
                providerId,
            )
            BudgetSnapshot(
                atMs = now,
                day = (row["day_tokens"] as Number).toLong(),
                month = (row["month_tokens"] as Number).toLong(),
            ).also { budgetCache[providerId] = it }
        } catch (e: Exception) {
            log.warn("llm_budget_query_failed provider={} reason={}", providerId, e.javaClass.simpleName)
            null
        }
    }
}
