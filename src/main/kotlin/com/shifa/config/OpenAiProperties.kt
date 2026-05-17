package com.shifa.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "openai")
class OpenAiProperties {

    lateinit var apiKey: String
    lateinit var projectId: String

    lateinit var model: String
    /** Embeddings model for clinical RAG (pgvector). */
    var embeddingModel: String = "text-embedding-3-small"
    /** Must match DB column `vector(...)` dimension in Flyway [clinical_rag_chunks.embedding]. */
    var embeddingDimensions: Int = 1536
    /** STT model for /v1/audio/transcriptions (e.g. gpt-4o-transcribe, gpt-4o-mini-transcribe, whisper-1). */
    var transcriptionModel: String = "gpt-4o-transcribe"
    var temperature: Double = 0.2
    var maxTokens: Int = 600
    var requestTimeoutMs: Long = 15_000

    var maxRequestsPerMinute: Int = 60
    var promptVersion: String = "1"
}
