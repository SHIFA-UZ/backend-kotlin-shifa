package com.shifa.ai.application

import com.shifa.ai.infrastructure.LlmProviderConfig
import com.shifa.ai.infrastructure.LlmUsageRecorder
import com.shifa.ai.domain.RequestCategory

import com.shifa.ai.domain.OutputLanguage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Turns (language, category) into an ordered provider chain using `ai.routes`.
 * Filters out ineligible, cooling-down, and over-budget providers; caps at max attempts.
 * Default (no routes configured) is `[ai.routing.default-provider]`.
 */
@Component
class LlmRouter(
    private val props: AiRoutingProperties,
    private val registry: LlmProviderRegistry,
    private val usage: LlmUsageRecorder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Candidates are the configured route for (language, category) followed by the default provider.
     * Providers that are eligible but not routed (e.g. grok enabled but absent from `routes.uz`) are
     * never used, so enabling a key does not move traffic until a route names it.
     */
    fun chain(language: OutputLanguage, category: RequestCategory, requireTools: Boolean): List<LlmProviderConfig> {
        val ordered = linkedSetOf<String>()
        ordered += configuredRoute(language, category)
        ordered += props.routing.defaultProvider

        val out = ArrayList<LlmProviderConfig>()
        for (id in ordered) {
            if (out.size >= props.routing.maxProviderAttempts.coerceAtLeast(1)) break
            val p = registry.get(id) ?: continue
            if (requireTools && !p.toolCalling) continue
            if (registry.isCoolingDown(id)) continue
            if (usage.isOverBudget(p)) {
                log.info("llm_route provider={} skipped=budget", id)
                continue
            }
            out += p
        }
        return out
    }

    fun single(id: String): LlmProviderConfig? = registry.get(id)

    private fun configuredRoute(language: OutputLanguage, category: RequestCategory): List<String> {
        if (!props.routing.enabled) return listOf(props.routing.defaultProvider)
        val byLang = props.routes[language.isoCode] ?: props.routes[language.name.lowercase()] ?: return emptyList()
        return byLang[category.name] ?: byLang[category.name.lowercase()] ?: byLang["default"] ?: emptyList()
    }
}
