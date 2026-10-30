package com.shifa.ai.infrastructure

import com.shifa.ai.domain.LlmErrorKind
import com.shifa.ai.domain.LlmException
import com.shifa.ai.domain.LlmRequest

import tools.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.TimeUnit

class OpenAiCompatibleLlmClientTest {
    private val server = MockWebServer()
    private val mapper = jacksonObjectMapper()
    private lateinit var client: OpenAiCompatibleLlmClient
    private lateinit var provider: LlmProviderConfig

    @BeforeEach
    fun setUp() {
        server.start()
        client = OpenAiCompatibleLlmClient(
            OkHttpClient.Builder().readTimeout(Duration.ofSeconds(2)).build(),
        )
        provider = LlmProviderConfig(
            id = "test",
            baseUrl = server.url("/v1").toString(),
            apiKey = "sk-secret",
            chatModel = "m1",
            projectId = "proj",
            timeoutMs = 1_000,
        )
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun ok(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun `builds request and normalizes content, tool calls and usage`() {
        server.enqueue(
            ok(
                """
                {"model":"m1-2025","choices":[{"finish_reason":"tool_calls","message":{"content":null,
                 "tool_calls":[{"id":"c1","type":"function","function":{"name":"list_free_slots","arguments":"{\"date\":\"today\"}"}}]}}],
                 "usage":{"prompt_tokens":120,"completion_tokens":15}}
                """.trimIndent(),
            ),
        )
        val resp = client.complete(
            LlmRequest(
                messages = listOf(mapOf("role" to "user", "content" to "free slots")),
                tools = listOf(mapOf("type" to "function", "function" to mapOf("name" to "list_free_slots"))),
                temperature = 0.0,
                maxTokens = 400,
            ),
            provider,
        )
        assertEquals("", resp.content)
        assertEquals(1, resp.toolCalls.size)
        assertEquals("list_free_slots", resp.toolCalls[0].name)
        assertEquals("today", resp.toolCalls[0].arguments["date"])
        assertEquals(120, resp.inputTokens)
        assertEquals(15, resp.outputTokens)
        assertEquals("m1-2025", resp.model)
        assertEquals("test", resp.providerId)

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.target)
        assertEquals("Bearer sk-secret", recorded.headers["Authorization"])
        assertEquals("proj", recorded.headers["OpenAI-Project"])
        val sent = mapper.readTree(recorded.body!!.utf8())
        assertEquals("m1", sent.path("model").asText())
        assertEquals(false, sent.path("stream").asBoolean())
        assertEquals("auto", sent.path("tool_choice").asText())
        assertTrue(sent.path("tools").isArray)
        assertTrue(sent.get("parallel_tool_calls") == null || sent.path("parallel_tool_calls").isMissingNode)
    }

    @Test
    fun `accepts object-form arguments and missing usage (gemini compat shape)`() {
        server.enqueue(
            ok(
                """
                {"choices":[{"message":{"content":"","tool_calls":[{"function":{"name":"get_today_schedule","arguments":{}}}]}}]}
                """.trimIndent(),
            ),
        )
        val resp = client.complete(LlmRequest(messages = emptyList(), tools = listOf(mapOf("x" to 1))), provider)
        assertEquals("get_today_schedule", resp.toolCalls.single().name)
        assertEquals("call_0", resp.toolCalls.single().id)
        assertNull(resp.inputTokens)
        assertEquals("m1", resp.model)
    }

    @Test
    fun `plain reply has no tool calls`() {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"  Hello doctor  "}}],"usage":{"prompt_tokens":1,"completion_tokens":2}}"""))
        val resp = client.complete(LlmRequest(messages = emptyList()), provider)
        assertEquals("Hello doctor", resp.content)
        assertTrue(resp.toolCalls.isEmpty())
        assertEquals("stop", resp.finishReason)
    }

    @Test
    fun `classifies rate limit vs quota exhaustion`() {
        server.enqueue(MockResponse(code = 429, body = """{"error":{"message":"Rate limit reached"}}"""))
        val rl = assertThrows<LlmException> { client.complete(LlmRequest(messages = emptyList()), provider) }
        assertEquals(LlmErrorKind.RATE_LIMITED, rl.kind)

        server.enqueue(MockResponse(code = 429, body = """{"error":{"type":"insufficient_quota","message":"You exceeded your current quota"}}"""))
        val q = assertThrows<LlmException> { client.complete(LlmRequest(messages = emptyList()), provider) }
        assertEquals(LlmErrorKind.QUOTA_EXHAUSTED, q.kind)
    }

    @Test
    fun `classifies auth, unsupported tools, invalid request, server error`() {
        server.enqueue(MockResponse(code = 401, body = "{}"))
        assertEquals(LlmErrorKind.AUTH, assertThrows<LlmException> { client.complete(LlmRequest(messages = emptyList()), provider) }.kind)

        server.enqueue(MockResponse(code = 400, body = """{"error":{"message":"tools[0].function.parameters invalid"}}"""))
        assertEquals(LlmErrorKind.UNSUPPORTED_TOOLS, assertThrows<LlmException> { client.complete(LlmRequest(messages = emptyList()), provider) }.kind)

        server.enqueue(MockResponse(code = 400, body = """{"error":{"message":"messages must not be empty"}}"""))
        assertEquals(LlmErrorKind.INVALID_REQUEST, assertThrows<LlmException> { client.complete(LlmRequest(messages = emptyList()), provider) }.kind)

        server.enqueue(MockResponse(code = 503, body = "upstream"))
        assertEquals(LlmErrorKind.SERVER, assertThrows<LlmException> { client.complete(LlmRequest(messages = emptyList()), provider) }.kind)
    }

    @Test
    fun `malformed body and timeout are classified`() {
        server.enqueue(ok("not json"))
        assertEquals(LlmErrorKind.MALFORMED, assertThrows<LlmException> { client.complete(LlmRequest(messages = emptyList()), provider) }.kind)

        server.enqueue(ok("""{"choices":[]}""").newBuilder().bodyDelay(3, TimeUnit.SECONDS).build())
        val t = assertThrows<LlmException> { client.complete(LlmRequest(messages = emptyList()), provider.copy(timeoutMs = 300)) }
        assertEquals(LlmErrorKind.TIMEOUT, t.kind)
    }

    @Test
    fun `provider without tool calling rejects tool requests locally`() {
        val ex = assertThrows<LlmException> {
            client.complete(LlmRequest(messages = emptyList(), tools = listOf(mapOf("x" to 1))), provider.copy(toolCalling = false))
        }
        assertEquals(LlmErrorKind.UNSUPPORTED_TOOLS, ex.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `json object mode and parallel tool calls flag are forwarded`() {
        server.enqueue(ok("""{"choices":[{"message":{"content":"{}"}}]}"""))
        client.complete(
            LlmRequest(messages = emptyList(), tools = listOf(mapOf("x" to 1)), jsonObject = true),
            provider.copy(parallelToolCalls = false),
        )
        val sent = mapper.readTree(server.takeRequest().body!!.utf8())
        assertEquals("json_object", sent.path("response_format").path("type").asText())
        assertEquals(false, sent.path("parallel_tool_calls").asBoolean())
    }

    @Test
    fun `gemini pads max tokens and sets low reasoning effort`() {
        server.enqueue(ok("""{"choices":[{"message":{"content":"ok"}}]}"""))
        client.complete(
            LlmRequest(messages = emptyList(), maxTokens = 120),
            provider.copy(id = "gemini"),
        )
        val sent = mapper.readTree(server.takeRequest().body!!.utf8())
        assertEquals("low", sent.path("reasoning_effort").asText())
        assertEquals(632, sent.path("max_tokens").asInt())
        assertTrue(sent.get("reasoning_effort") != null)
    }
}
