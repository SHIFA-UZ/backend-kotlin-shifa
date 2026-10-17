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
    /** STT model for /v1/audio/transcriptions. Prefer gpt-4o-transcribe (best accuracy). */
    var transcriptionModel: String = "gpt-4o-transcribe"
    /**
     * When true and a language hint is set, sends `language=uz|ru|en` for gpt-4o-* transcribe models as well.
     * Set OPENAI_TRANSCRIPTION_SEND_LANG=false if the API rejects a code (temporary rollback).
     */
    var transcriptionSendLanguageParam: Boolean = true
    /** Enables post-transcription GPT fix pass for spelling of medical terms. */
    var transcriptionMedicalCleanupEnabled: Boolean = true
    /** Apply cleanup also to mic-upload STT (/copilot/transcribe, /api/ai/transcribe). Defaults off (scribe only). */
    var transcriptionMedicalCleanupVoiceUploads: Boolean = false
    /** Skip cleanup below this token count / word-ish count to avoid needless API calls on very short phrases. */
    var transcriptionMedicalCleanupMinWords: Int = 10
    /** Lightweight model used only for typo cleanup of transcripts. */
    var transcriptionMedicalCleanupModel: String = "gpt-4o-mini"
    var temperature: Double = 0.2
    var maxTokens: Int = 600
    var requestTimeoutMs: Long = 15_000

    /** JVM-wide coarse backstop across all OpenAI calls in this pod (distinct from per-user AI HTTP limits). */
    var maxRequestsPerMinute: Int = 600
    var promptVersion: String = "1"

    /** OpenAI TTS model for Intelligence spoken replies. Prefer fast tts-1 (latency over HD). */
    var ttsModel: String = "tts-1"
    /** OpenAI TTS voice for tts-1 (alloy/ash/coral/echo/fable/onyx/nova/sage/shimmer). */
    var ttsVoice: String = "sage"
    /**
     * 1.0 is OpenAI's default and sounds rushed on lists of names and times.
     * A little under 1.0 keeps short greetings and long lists at one pace.
     */
    var ttsSpeed: Double = 0.92
}
