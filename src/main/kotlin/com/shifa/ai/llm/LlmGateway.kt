package com.shifa.ai.llm

import com.shifa.ai.OutputLanguage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/** Per-turn routing context. [pinnedProvider] is set once a tool has executed in this turn. */
class LlmTurnContext(
    val doctorId: Long?,
    val language: OutputLanguage,
    val category: RequestCategory,
    val requestId: String = UUID.randomUUID().toString().take(32),
    val startedAtMs: Long = System.currentTimeMillis(),
) {
    @Volatile
    var pinnedProvider: String? = null
}

class LlmUnavailableException(val attempts: List<Pair<String, LlmErrorKind>>, message: String) : RuntimeException(message)

/**
 * Routed completion with bounded fallback and usage accounting.
 *
 * Fallback happens only when the turn is not pinned. Once the caller has executed a tool on a
 * provider's output, it pins the turn; a later failure then surfaces as [LlmUnavailableException]
 * instead of replaying the conversation on another provider.
 */
@Component
class LlmGateway(
    private val client: LlmClient,
    private val router: LlmRouter,
    private val registry: LlmProviderRegistry,
    private val usage: LlmUsageRecorder,
    private val props: AiRoutingProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun complete(
        request: LlmRequest,
        ctx: LlmTurnContext,
        purpose: LlmPurpose,
        toolNamesForLog: List<String> = emptyList(),
    ): LlmResponse {
        val requireTools = !request.tools.isNullOrEmpty()
        val chain: List<LlmProviderConfig> = ctx.pinnedProvider?.let { id ->
            listOfNotNull(registry.get(id))
        } ?: router.chain(ctx.language, ctx.category, requireTools)

        if (chain.isEmpty()) {
            throw LlmUnavailableException(emptyList(), "no eligible LLM provider for lang=${ctx.language.isoCode}")
        }

        val attempts = ArrayList<Pair<String, LlmErrorKind>>()
        for ((index, provider) in chain.withIndex()) {
            val elapsed = clock.millis() - ctx.startedAtMs
            if (index > 0 && elapsed >= props.routing.requestDeadlineMs) {
                log.warn("llm_deadline requestId={} elapsedMs={}", ctx.requestId, elapsed)
                break
            }
            try {
                val response = client.complete(request, provider)
                registry.clearCooldown(provider.id)
                usage.record(
                    LlmUsageRecorder.Record(
                        requestId = ctx.requestId, doctorId = ctx.doctorId, providerId = provider.id, model = response.model,
                        language = ctx.language, category = ctx.category, purpose = purpose,
                        inputTokens = response.inputTokens, outputTokens = response.outputTokens,
                        durationMs = response.durationMs, outcome = "SUCCESS", fallbackIndex = index,
                        toolNames = response.toolCalls.map { it.name }.ifEmpty { toolNamesForLog },
                    ),
                )
                return response
            } catch (e: LlmException) {
                attempts += provider.id to e.kind
                usage.record(
                    LlmUsageRecorder.Record(
                        requestId = ctx.requestId, doctorId = ctx.doctorId, providerId = provider.id, model = provider.chatModel,
                        language = ctx.language, category = ctx.category, purpose = purpose,
                        inputTokens = null, outputTokens = null, durationMs = 0, outcome = e.kind.name, fallbackIndex = index,
                        toolNames = toolNamesForLog,
                    ),
                )
                if (e.kind.triggersCooldown) registry.markCooldown(provider.id)
                if (!e.kind.retryableOnOtherProvider) break
                if (ctx.pinnedProvider != null) break
            }
        }
        throw LlmUnavailableException(attempts, "all LLM providers failed: $attempts")
    }
}
