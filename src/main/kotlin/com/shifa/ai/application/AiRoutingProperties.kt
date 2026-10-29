package com.shifa.ai.application

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `ai.*` configuration: providers, routing policy, usage budgets, memory.
 *
 * Defaults keep OpenAI as the only route. Optional providers (gemini, grok, open) are
 * ignored unless `enabled: true` AND api-key AND chat-model are all present.
 */
@ConfigurationProperties(prefix = "ai")
class AiRoutingProperties {
    var routing: Routing = Routing()
    var providers: MutableMap<String, Provider> = linkedMapOf()
    /** language code (en|ru|uz) -> (category name or "default") -> ordered provider ids. */
    var routes: MutableMap<String, MutableMap<String, MutableList<String>>> = linkedMapOf()
    var memory: Memory = Memory()
    var speech: Speech = Speech()

    class Routing {
        var enabled: Boolean = true
        var defaultProvider: String = "openai"
        /** Max distinct providers tried for one model call. */
        var maxProviderAttempts: Int = 2
        /** Whole-turn deadline for the tool loop + spoken reply. */
        var requestDeadlineMs: Long = 40_000
        /** How long a provider is skipped after a cooldown-triggering failure. */
        var cooldownMs: Long = 60_000
        /** Cache window for DB budget sums. */
        var budgetCacheMs: Long = 60_000
    }

    class Provider {
        var enabled: Boolean = false
        var baseUrl: String = ""
        var apiKey: String = ""
        var chatModel: String = ""
        /** Optional extra header (OpenAI project). Only sent when non-blank. */
        var projectId: String = ""
        var timeoutMs: Long = 45_000
        var maxOutputTokens: Int = 1024
        /** 0 = no local budget. Local accounting only; not a billing ceiling. */
        var dailyTokenBudget: Long = 0
        var monthlyTokenBudget: Long = 0
        var toolCalling: Boolean = true
        var parallelToolCalls: Boolean = true
        var jsonObject: Boolean = true

        override fun toString(): String =
            "Provider(enabled=$enabled, baseUrl=$baseUrl, chatModel=$chatModel, apiKey=${if (apiKey.isBlank()) "<blank>" else "<set>"})"
    }

    class Memory {
        var enabled: Boolean = true
        /** Provider id used for memory candidate extraction (never the doctor-facing route). */
        var extractionProvider: String = "openai"
        var maxRetrieved: Int = 5
        var minConfidence: Double = 0.7
        var temporaryTtlMinutes: Long = 120
    }

    /**
     * Intelligence voice. Uzbek (and auto-detect mic) uses Gemini speech models when a Gemini key exists.
     * English/Russian TTS and scribe STT stay on OpenAI.
     */
    class Speech {
        var uzbekProvider: String = "gemini"
        var sttModel: String = "gemini-3.5-transcribe"
        var ttsModel: String = "gemini-3.8-flash-lite-tts"
        var ttsVoice: String = "Kore"
    }
}
