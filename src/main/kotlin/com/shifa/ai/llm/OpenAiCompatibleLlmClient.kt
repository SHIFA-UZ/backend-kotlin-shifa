package com.shifa.ai.llm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Resolved, validated provider used for one call. Never log [apiKey]. */
data class LlmProviderConfig(
    val id: String,
    val baseUrl: String,
    val apiKey: String,
    val chatModel: String,
    val projectId: String = "",
    val timeoutMs: Long = 45_000,
    val maxOutputTokens: Int = 1024,
    val dailyTokenBudget: Long = 0,
    val monthlyTokenBudget: Long = 0,
    val toolCalling: Boolean = true,
    val parallelToolCalls: Boolean = true,
    val jsonObject: Boolean = true,
) {
    override fun toString(): String = "LlmProviderConfig(id=$id, model=$chatModel, baseUrl=$baseUrl)"
}

interface LlmClient {
    fun complete(request: LlmRequest, provider: LlmProviderConfig): LlmResponse
}

/**
 * Chat-completions client for any OpenAI-compatible endpoint (OpenAI, Gemini compat, xAI, vLLM, ...).
 * Non-streaming only: the morning assistant builds SSE events itself after each call.
 */
@Component
class OpenAiCompatibleLlmClient(
    baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .writeTimeout(Duration.ofSeconds(15))
        .readTimeout(Duration.ofSeconds(45))
        .build(),
) : LlmClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()
    private val http = baseClient

    override fun complete(request: LlmRequest, provider: LlmProviderConfig): LlmResponse {
        val asked = request.maxTokens.coerceAtMost(provider.maxOutputTokens)
        val gemini = provider.id.equals("gemini", ignoreCase = true)
        val body = linkedMapOf<String, Any?>(
            "model" to provider.chatModel,
            "stream" to false,
            "temperature" to request.temperature,
            // Gemini 3 thinking counts against max_tokens; pad so the spoken line is not a stub.
            "max_tokens" to if (gemini) {
                (asked + 512).coerceAtMost(maxOf(provider.maxOutputTokens, asked + 512))
            } else {
                asked
            },
            "messages" to request.messages,
        )
        if (gemini) {
            body["reasoning_effort"] = "low"
        }
        if (!request.tools.isNullOrEmpty()) {
            if (!provider.toolCalling) {
                throw LlmException(LlmErrorKind.UNSUPPORTED_TOOLS, provider.id, null, "provider has tool-calling disabled")
            }
            body["tools"] = request.tools
            body["tool_choice"] = request.toolChoice ?: "auto"
            // Do not send parallel_tool_calls: Gemini's OpenAI-compat endpoint 400s on unknown fields.
            // The morning loop already caps and serializes whatever tool_calls come back.
        }
        if (request.jsonObject && provider.jsonObject) {
            body["response_format"] = mapOf("type" to "json_object")
        }
        val payload = mapper.writeValueAsString(body)
        val httpReq = Request.Builder()
            .url(provider.baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer ${provider.apiKey}")
            .addHeader("Content-Type", "application/json")
            .apply { if (provider.projectId.isNotBlank()) addHeader("OpenAI-Project", provider.projectId) }
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val call = http.newBuilder()
            .readTimeout(provider.timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(provider.timeoutMs + 5_000, TimeUnit.MILLISECONDS)
            .build()
            .newCall(httpReq)

        val started = System.nanoTime()
        val (status, text) = try {
            call.execute().use { resp -> resp.code to (resp.body?.string().orEmpty()) }
        } catch (e: InterruptedIOException) {
            // SocketTimeoutException and OkHttp call timeouts both extend InterruptedIOException.
            throw LlmException(LlmErrorKind.TIMEOUT, provider.id, null, "timeout", e)
        } catch (e: IOException) {
            throw LlmException(LlmErrorKind.CONNECTION, provider.id, null, "connection failure", e)
        }
        val durationMs = (System.nanoTime() - started) / 1_000_000

        if (status !in 200..299) {
            throw classify(status, text, provider.id)
        }
        val json = try {
            mapper.readTree(text)
        } catch (e: Exception) {
            throw LlmException(LlmErrorKind.MALFORMED, provider.id, status, "unparsable JSON", e)
        }
        return normalize(json, provider, durationMs)
    }

    private fun normalize(json: JsonNode, provider: LlmProviderConfig, durationMs: Long): LlmResponse {
        val choice = json.path("choices").path(0)
        if (choice.isMissingNode) {
            throw LlmException(LlmErrorKind.MALFORMED, provider.id, 200, "no choices in response")
        }
        val msg = choice.path("message")
        val content = msg.path("content").let { if (it.isNull || it.isMissingNode) "" else it.asText("") }.trim()
        val toolCalls = msg.path("tool_calls").takeIf { it.isArray }?.mapIndexedNotNull { idx, tc ->
            val fn = tc.path("function")
            val name = fn.path("name").asText(null)?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            val id = tc.path("id").asText(null)?.takeIf { it.isNotBlank() } ?: "call_$idx"
            LlmToolCall(id = id, name = name, arguments = parseArguments(fn.path("arguments")))
        } ?: emptyList()
        val usage = json.path("usage")
        return LlmResponse(
            content = content,
            toolCalls = toolCalls,
            finishReason = choice.path("finish_reason").asText(null),
            inputTokens = usage.path("prompt_tokens").takeIf { it.isNumber }?.asInt(),
            outputTokens = usage.path("completion_tokens").takeIf { it.isNumber }?.asInt(),
            providerId = provider.id,
            model = json.path("model").asText(null)?.takeIf { it.isNotBlank() } ?: provider.chatModel,
            durationMs = durationMs,
        )
    }

    /** OpenAI sends `arguments` as a JSON string; some compat servers send an object. */
    @Suppress("UNCHECKED_CAST")
    private fun parseArguments(node: JsonNode): Map<String, Any?> = try {
        when {
            node.isObject -> mapper.convertValue(node, Map::class.java) as Map<String, Any?>
            node.isTextual -> {
                val raw = node.asText().trim()
                if (raw.isEmpty()) emptyMap() else mapper.readValue(raw, Map::class.java) as Map<String, Any?>
            }
            else -> emptyMap()
        }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun classify(status: Int, body: String, providerId: String): LlmException {
        val lower = body.lowercase()
        val kind = when {
            status == 429 && (lower.contains("insufficient_quota") || lower.contains("quota") || lower.contains("billing") || lower.contains("exceeded your current")) ->
                LlmErrorKind.QUOTA_EXHAUSTED
            status == 429 -> LlmErrorKind.RATE_LIMITED
            status == 401 || status == 403 -> LlmErrorKind.AUTH
            status == 400 && (lower.contains("tool") || lower.contains("function")) -> LlmErrorKind.UNSUPPORTED_TOOLS
            status in 400..499 -> LlmErrorKind.INVALID_REQUEST
            status in 500..599 -> LlmErrorKind.SERVER
            else -> LlmErrorKind.UNKNOWN
        }
        val snippet = body.replace(Regex("\\s+"), " ").take(180)
        // Provider error bodies can echo prompt text; keep them out of INFO/WARN logs.
        log.warn("llm_http provider={} status={} kind={}", providerId, status, kind)
        log.debug("llm_http provider={} body={}", providerId, snippet)
        return LlmException(kind, providerId, status, "HTTP $status")
    }
}
