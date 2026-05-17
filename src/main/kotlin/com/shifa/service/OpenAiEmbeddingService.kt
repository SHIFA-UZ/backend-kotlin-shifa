package com.shifa.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.shifa.config.OpenAiProperties
import com.shifa.web.AiStreamException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * OpenAI `/v1/embeddings` for clinical RAG (batch-capable).
 */
@Service
class OpenAiEmbeddingService(
    private val props: OpenAiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()

    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .writeTimeout(Duration.ofSeconds(60))
        .readTimeout(Duration.ofSeconds(120))
        .build()

    /** One embedding per input string; every string must be non-blank. */
    fun embedTexts(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        require(texts.all { it.isNotBlank() }) { "embedTexts requires non-blank strings" }

        val out = ArrayList<FloatArray>(texts.size)
        val batchSize = 24
        for (batch in texts.chunked(batchSize)) {
            val payload = mapper.writeValueAsString(
                mapOf(
                    "model" to props.embeddingModel,
                    "input" to batch,
                    "dimensions" to props.embeddingDimensions,
                )
            )
            val request = Request.Builder()
                .url("https://api.openai.com/v1/embeddings")
                .addHeader("Authorization", "Bearer ${props.apiKey}")
                .addHeader("OpenAI-Project", props.projectId)
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    log.warn("OpenAI embeddings HTTP {}", response.code)
                    throw AiStreamException("AI_UNAVAILABLE", "Embedding service unavailable.")
                }
                val body = response.body?.string().orEmpty()
                val json = mapper.readTree(body)
                val data = json.path("data")
                if (!data.isArray || data.size() != batch.size) {
                    throw AiStreamException("AI_UNAVAILABLE", "Unexpected embeddings response shape.")
                }
                for (node in data) {
                    out += parseEmbedding(node.path("embedding"))
                }
            }
        }
        return out
    }

    private fun parseEmbedding(node: JsonNode): FloatArray {
        if (!node.isArray) {
            throw AiStreamException("AI_UNAVAILABLE", "Embedding vector missing.")
        }
        val dims = props.embeddingDimensions
        if (node.size() != dims) {
            log.warn("Embedding dim mismatch: expected {}, got {}", dims, node.size())
        }
        val arr = FloatArray(node.size()) { i -> node[i].floatValue() }
        require(arr.size == dims) {
            "Embedding dimension ${arr.size} != configured $dims"
        }
        return arr
    }
}
