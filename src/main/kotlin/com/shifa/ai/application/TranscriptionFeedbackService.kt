package com.shifa.ai.application

import tools.jackson.module.kotlin.jacksonObjectMapper
import com.shifa.shared.storage.FileStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.Locale
import java.util.UUID

@Service
class TranscriptionFeedbackService(
    private val storage: FileStorage,
) {
    enum class FeedbackSource {
        PATIENT_COPILOT,
        DOCTOR_STT
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()

    fun save(
        source: FeedbackSource,
        userId: Long,
        transcript: String,
        locale: String?,
        notes: String?,
        audioFilename: String?,
        audioMimeType: String?,
        audioBytes: ByteArray?,
    ): String {
        // "private/" is never served by StoredFileController.
        val keyPrefix = "private/transcription-feedback"

        val id = UUID.randomUUID().toString()
        val sanitizedTranscript =
            transcript.replace(Regex("\\p{C}"), "").take(MAX_TRANSCRIPT_CHARS_STORED)

        val metaKey = "$keyPrefix/${id}_meta.json"
        val suffixForFile = audioFilename?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
            ?: deriveSafeSuffix(audioMimeType)
        val audit = mutableMapOf(
            "id" to id,
            "createdAt" to Instant.now().toString(),
            "source" to source.name,
            "userId" to userId,
            "locale" to locale,
            "notes" to notes?.take(2000),
            "transcript" to sanitizedTranscript,
            "audioOriginalName" to audioFilename?.takeIf { it.isNotBlank() },
            "audioMime" to audioMimeType?.takeIf { it.isNotBlank() },
            "storedAudioSuffix" to suffixForFile,
        )
        val meta = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(audit)
        storage.put(metaKey, ByteArrayInputStream(meta), meta.size.toLong(), "application/json")

        audioBytes?.let { bytes ->
            val ext = sanitizeExt(suffixForFile)
            storage.put("$keyPrefix/${id}_audio.$ext", ByteArrayInputStream(bytes), bytes.size.toLong(), audioMimeType?.takeIf { it.isNotBlank() })
        }

        log.info("Stored transcription QA feedback {} at {}", id, metaKey.substringAfterLast('/'))
        return id
    }

    companion object {
        private const val MAX_TRANSCRIPT_CHARS_STORED = 120_000

        private fun deriveSafeSuffix(mime: String?): String {
            val m = mime?.lowercase(Locale.ROOT) ?: ""
            return when {
                m.contains("m4a") || m.contains("mp4") || m.contains("mpeg4-audio") -> "m4a"
                m.contains("wav") -> "wav"
                m.contains("webm") -> "webm"
                m.contains("mp3") || m.contains("mpeg") -> "mp3"
                else -> "bin"
            }
        }

        /** Allow only alphanumeric extension segment. */
        private fun sanitizeExt(raw: String): String {
            val s = raw.replace(Regex("[^a-zA-Z0-9]"), "").take(8).lowercase(Locale.ROOT)
            return if (s.isBlank()) "bin" else s
        }
    }
}
