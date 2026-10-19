package com.shifa.ai.llm

import com.shifa.config.OpenAiProperties
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Validated providers + in-JVM cooldown state.
 *
 * A provider is eligible only when enabled with a non-blank api-key, chat-model and parsable base-url.
 * When `ai.providers.openai` is absent, an `openai` provider is synthesized from [OpenAiProperties]
 * so existing deployments keep working without new configuration.
 *
 * Cooldowns are per JVM. Railway production runs a single instance; replicas would not share state.
 */
@Component
class LlmProviderRegistry(
    private val props: AiRoutingProperties,
    private val openAi: OpenAiProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val eligible = linkedMapOf<String, LlmProviderConfig>()
    private val cooldownUntil = ConcurrentHashMap<String, Long>()

    @PostConstruct
    fun init() {
        eligible.clear()
        val configured = LinkedHashMap(props.providers)
        if (!configured.containsKey("openai")) {
            configured["openai"] = AiRoutingProperties.Provider().apply {
                enabled = true
                baseUrl = "https://api.openai.com/v1"
                apiKey = runCatching { openAi.apiKey }.getOrDefault("")
                projectId = runCatching { openAi.projectId }.getOrDefault("")
                chatModel = runCatching { openAi.model }.getOrDefault("gpt-4o-mini")
            }
        } else {
            // Fill blanks on the explicit openai entry from legacy properties.
            configured.getValue("openai").let { p ->
                if (p.apiKey.isBlank()) p.apiKey = runCatching { openAi.apiKey }.getOrDefault("")
                if (p.projectId.isBlank()) p.projectId = runCatching { openAi.projectId }.getOrDefault("")
                if (p.chatModel.isBlank()) p.chatModel = runCatching { openAi.model }.getOrDefault("gpt-4o-mini")
                if (p.baseUrl.isBlank()) p.baseUrl = "https://api.openai.com/v1"
            }
        }
        for ((id, p) in configured) {
            val reason = validationError(p)
            if (reason != null) {
                if (p.enabled) log.warn("llm_provider id={} excluded reason={}", id, reason)
                else log.info("llm_provider id={} disabled", id)
                continue
            }
            eligible[id] = LlmProviderConfig(
                id = id,
                baseUrl = p.baseUrl.trim(),
                apiKey = p.apiKey,
                chatModel = p.chatModel.trim(),
                projectId = p.projectId.trim(),
                timeoutMs = p.timeoutMs,
                maxOutputTokens = p.maxOutputTokens,
                dailyTokenBudget = p.dailyTokenBudget,
                monthlyTokenBudget = p.monthlyTokenBudget,
                toolCalling = p.toolCalling,
                parallelToolCalls = p.parallelToolCalls,
                jsonObject = p.jsonObject,
            )
            log.info("llm_provider id={} model={} baseUrl={} eligible", id, p.chatModel, p.baseUrl)
        }
    }

    fun get(id: String): LlmProviderConfig? = eligible[id]

    fun eligibleIds(): List<String> = eligible.keys.toList()

    fun isCoolingDown(id: String): Boolean {
        val until = cooldownUntil[id] ?: return false
        if (until <= clock.millis()) {
            cooldownUntil.remove(id)
            return false
        }
        return true
    }

    fun markCooldown(id: String, durationMs: Long = props.routing.cooldownMs) {
        if (durationMs <= 0) return
        cooldownUntil[id] = clock.millis() + durationMs
        log.warn("llm_provider id={} cooldownMs={}", id, durationMs)
    }

    fun clearCooldown(id: String) {
        cooldownUntil.remove(id)
    }

    private fun validationError(p: AiRoutingProperties.Provider): String? {
        if (!p.enabled) return "disabled"
        if (p.apiKey.isBlank()) return "missing api-key"
        if (p.chatModel.isBlank()) return "missing chat-model"
        if (p.baseUrl.isBlank()) return "missing base-url"
        val uri = runCatching { URI(p.baseUrl.trim()) }.getOrNull() ?: return "invalid base-url"
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return "invalid base-url"
        return null
    }
}
