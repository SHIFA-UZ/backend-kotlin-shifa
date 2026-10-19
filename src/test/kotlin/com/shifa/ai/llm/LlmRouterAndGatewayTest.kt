package com.shifa.ai.llm

import com.shifa.ai.OutputLanguage
import com.shifa.config.OpenAiProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class LlmRouterAndGatewayTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    companion object {
        fun ok(providerId: String, content: String = "hi", tools: List<LlmToolCall> = emptyList()) =
            LlmResponse(content, tools, "stop", 10, 5, providerId, "model-$providerId", 12)
    }

    /** Scripted fake client: per provider id, a queue of outcomes. */
    private class FakeClient : LlmClient {
        val script = HashMap<String, ArrayDeque<Any>>()
        val calls = ArrayList<String>()
        fun on(id: String, vararg outcomes: Any) {
            script.getOrPut(id) { ArrayDeque() }.addAll(outcomes)
        }
        override fun complete(request: LlmRequest, provider: LlmProviderConfig): LlmResponse {
            calls += provider.id
            val next = script[provider.id]?.removeFirstOrNull() ?: ok(provider.id)
            if (next is LlmErrorKind) throw LlmException(next, provider.id, null, next.name)
            return next as LlmResponse
        }
    }

    private fun provider(key: String = "k", tools: Boolean = true, daily: Long = 0) = AiRoutingProperties.Provider().apply {
        enabled = true; apiKey = key; chatModel = "m"; baseUrl = "https://x.example/v1"; toolCalling = tools; dailyTokenBudget = daily
    }

    private fun build(
        configure: AiRoutingProperties.() -> Unit,
        clock: Clock = Clock.systemUTC(),
        jdbc: JdbcTemplate = mock(JdbcTemplate::class.java),
    ): Triple<LlmGateway, FakeClient, LlmProviderRegistry> {
        val props = AiRoutingProperties().apply(configure)
        val openAi = OpenAiProperties().apply { apiKey = "sk"; projectId = ""; model = "gpt-4o-mini" }
        val registry = LlmProviderRegistry(props, openAi, clock).also { it.init() }
        val usage = LlmUsageRecorder(jdbc, props, clock)
        val router = LlmRouter(props, registry, usage)
        val client = FakeClient()
        return Triple(LlmGateway(client, router, registry, usage, props, clock), client, registry)
    }

    private fun ctx(lang: OutputLanguage = OutputLanguage.EN) =
        LlmTurnContext(doctorId = 1, language = lang, category = RequestCategory.SCHEDULE_QUERY)

    private val toolReq = LlmRequest(messages = emptyList(), tools = listOf(mapOf("t" to 1)))

    @Test
    fun `default configuration routes everything to openai only`() {
        val (gw, client, _) = build({})
        gw.complete(toolReq, ctx(OutputLanguage.UZ), LlmPurpose.TOOL_LOOP)
        gw.complete(toolReq, ctx(OutputLanguage.RU), LlmPurpose.TOOL_LOOP)
        assertEquals(listOf("openai", "openai"), client.calls)
    }

    @Test
    fun `uzbek route prefers gemini when configured and falls back to openai on rate limit`() {
        val (gw, client, registry) = build({
            providers["openai"] = provider()
            providers["gemini"] = provider()
            routes["uz"] = mutableMapOf("default" to mutableListOf("gemini", "openai"))
        })
        client.on("gemini", LlmErrorKind.RATE_LIMITED)
        val resp = gw.complete(toolReq, ctx(OutputLanguage.UZ), LlmPurpose.TOOL_LOOP)
        assertEquals("openai", resp.providerId)
        assertEquals(listOf("gemini", "openai"), client.calls)
        assertTrue(registry.isCoolingDown("gemini"))

        // While cooling down, the next Uzbek turn skips gemini without calling it.
        client.calls.clear()
        gw.complete(toolReq, ctx(OutputLanguage.UZ), LlmPurpose.TOOL_LOOP)
        assertEquals(listOf("openai"), client.calls)
    }

    @Test
    fun `uzbek production chain skips gemini without a key and uses openai`() {
        val (gw, client, _) = build({
            providers["openai"] = provider()
            providers["gemini"] = provider(key = "")
            routes["uz"] = mutableMapOf("default" to mutableListOf("gemini", "openai"))
        })
        gw.complete(toolReq, ctx(OutputLanguage.UZ), LlmPurpose.TOOL_LOOP)
        assertEquals(listOf("openai"), client.calls)
    }

    @Test
    fun `english production chain uses grok as fallback when keyed`() {
        val (gw, client, _) = build({
            providers["openai"] = provider()
            providers["grok"] = provider()
            routes["en"] = mutableMapOf("default" to mutableListOf("openai", "grok"))
        })
        client.on("openai", LlmErrorKind.RATE_LIMITED)
        val resp = gw.complete(toolReq, ctx(OutputLanguage.EN), LlmPurpose.TOOL_LOOP)
        assertEquals("grok", resp.providerId)
        assertEquals(listOf("openai", "grok"), client.calls)
    }

    @Test
    fun `eligible but unrouted provider never receives traffic`() {
        val (gw, client, _) = build({
            providers["openai"] = provider()
            providers["grok"] = provider()
            routes["en"] = mutableMapOf("default" to mutableListOf("openai"))
        })
        client.on("openai", LlmErrorKind.SERVER)
        assertThrows<LlmUnavailableException> { gw.complete(toolReq, ctx(OutputLanguage.EN), LlmPurpose.TOOL_LOOP) }
        assertEquals(listOf("openai"), client.calls)
    }

    @Test
    fun `per-category route overrides language default`() {
        val (gw, client, _) = build({
            providers["openai"] = provider()
            providers["grok"] = provider()
            routes["en"] = mutableMapOf(
                "default" to mutableListOf("openai"),
                "SCHEDULE_QUERY" to mutableListOf("grok", "openai"),
            )
        })
        gw.complete(toolReq, ctx(OutputLanguage.EN), LlmPurpose.TOOL_LOOP)
        assertEquals(listOf("grok"), client.calls)
    }

    @Test
    fun `max provider attempts bounds the chain`() {
        val (gw, client, _) = build({
            routing.maxProviderAttempts = 2
            providers["openai"] = provider(); providers["gemini"] = provider(); providers["grok"] = provider()
            routes["en"] = mutableMapOf("default" to mutableListOf("gemini", "grok", "openai"))
        })
        client.on("gemini", LlmErrorKind.TIMEOUT)
        client.on("grok", LlmErrorKind.TIMEOUT)
        assertThrows<LlmUnavailableException> { gw.complete(toolReq, ctx(), LlmPurpose.TOOL_LOOP) }
        assertEquals(listOf("gemini", "grok"), client.calls)
    }

    @Test
    fun `invalid request does not fall through to other providers`() {
        val (gw, client, _) = build({
            providers["openai"] = provider(); providers["gemini"] = provider()
            routes["en"] = mutableMapOf("default" to mutableListOf("gemini", "openai"))
        })
        client.on("gemini", LlmErrorKind.INVALID_REQUEST)
        assertThrows<LlmUnavailableException> { gw.complete(toolReq, ctx(), LlmPurpose.TOOL_LOOP) }
        assertEquals(listOf("gemini"), client.calls)
    }

    @Test
    fun `unsupported tool schema skips to next provider without simplifying tools`() {
        val (gw, client, _) = build({
            providers["openai"] = provider(); providers["gemini"] = provider()
            routes["en"] = mutableMapOf("default" to mutableListOf("gemini", "openai"))
        })
        client.on("gemini", LlmErrorKind.UNSUPPORTED_TOOLS)
        val resp = gw.complete(toolReq, ctx(), LlmPurpose.TOOL_LOOP)
        assertEquals("openai", resp.providerId)
    }

    @Test
    fun `provider with tool calling disabled is skipped for tool requests`() {
        val (gw, client, _) = build({
            providers["openai"] = provider(); providers["gemini"] = provider(tools = false)
            routes["en"] = mutableMapOf("default" to mutableListOf("gemini", "openai"))
        })
        gw.complete(toolReq, ctx(), LlmPurpose.TOOL_LOOP)
        assertEquals(listOf("openai"), client.calls)
        client.calls.clear()
        gw.complete(LlmRequest(messages = emptyList()), ctx(), LlmPurpose.SPOKEN_REPLY)
        assertEquals(listOf("gemini"), client.calls)
    }

    @Test
    fun `pinned turn never falls back to another provider`() {
        val (gw, client, _) = build({
            providers["openai"] = provider(); providers["gemini"] = provider()
            routes["en"] = mutableMapOf("default" to mutableListOf("gemini", "openai"))
        })
        val c = ctx()
        c.pinnedProvider = "gemini"
        client.on("gemini", LlmErrorKind.SERVER)
        assertThrows<LlmUnavailableException> { gw.complete(toolReq, c, LlmPurpose.TOOL_LOOP) }
        assertEquals(listOf("gemini"), client.calls)
    }

    @Test
    fun `request deadline stops further attempts`() {
        val clock = MutableClock(Instant.parse("2026-10-19T08:00:00Z"))
        val (gw, client, _) = build({
            routing.requestDeadlineMs = 1_000
            providers["openai"] = provider(); providers["gemini"] = provider()
            routes["en"] = mutableMapOf("default" to mutableListOf("gemini", "openai"))
        }, clock)
        val c = LlmTurnContext(1, OutputLanguage.EN, RequestCategory.GENERAL_ASSISTANCE, startedAtMs = clock.millis())
        client.on("gemini", LlmErrorKind.TIMEOUT)
        clock.now = clock.now.plusSeconds(5)
        assertThrows<LlmUnavailableException> { gw.complete(toolReq, c, LlmPurpose.TOOL_LOOP) }
        assertEquals(listOf("gemini"), client.calls)
    }

    @Test
    fun `provider over its daily budget is skipped`() {
        val jdbc = mock(JdbcTemplate::class.java)
        `when`(jdbc.queryForMap(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("gemini")))
            .thenReturn(mapOf("day_tokens" to 5_000L, "month_tokens" to 5_000L))
        val (gw, client, _) = build({
            providers["openai"] = provider(); providers["gemini"] = provider(daily = 1_000)
            routes["uz"] = mutableMapOf("default" to mutableListOf("gemini", "openai"))
        }, jdbc = jdbc)
        gw.complete(toolReq, ctx(OutputLanguage.UZ), LlmPurpose.TOOL_LOOP)
        assertEquals(listOf("openai"), client.calls)
    }

    @Test
    fun `usage row is written for success and failure`() {
        val jdbc = mock(JdbcTemplate::class.java)
        val (gw, client, _) = build({
            providers["openai"] = provider(); providers["gemini"] = provider()
            routes["en"] = mutableMapOf("default" to mutableListOf("gemini", "openai"))
        }, jdbc = jdbc)
        client.on("gemini", LlmErrorKind.SERVER)
        gw.complete(toolReq, ctx(), LlmPurpose.TOOL_LOOP)
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.times(2))
            .update(org.mockito.ArgumentMatchers.contains("INSERT INTO ai_llm_request_log"), *Array<Any?>(13) { org.mockito.ArgumentMatchers.any() })
    }
}
