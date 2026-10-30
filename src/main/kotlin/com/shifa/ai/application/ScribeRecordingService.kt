package com.shifa.ai.application


import tools.jackson.module.kotlin.jacksonObjectMapper
import com.shifa.shared.config.DailyProperties
import com.shifa.shared.config.ScribeProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/**
 * Handles recording download and temp storage for the AI scribe pipeline.
 */
@Service
class ScribeRecordingService(
    private val scribeProps: ScribeProperties,
    private val dailyProps: DailyProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    init {
        Files.createDirectories(Path.of(scribeProps.tempDir))
    }

    /**
     * Download file from URL to temp directory.
     */
    fun downloadFromUrl(url: String): Path {
        val ext = url.substringAfterLast('.').takeIf { it.length in 1..5 } ?: "mp4"
        val tempPath = Path.of(scribeProps.tempDir, "${UUID.randomUUID()}.$ext")

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMinutes(5))
            .GET()
            .build()

        log.info("Downloading recording from URL to {}", tempPath.fileName)
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tempPath))

        if (response.statusCode() !in 200..299) {
            Files.deleteIfExists(tempPath)
            throw RuntimeException("Download failed: HTTP ${response.statusCode()}")
        }

        log.info("Downloaded {} bytes to {}", Files.size(tempPath), tempPath.fileName)
        return tempPath
    }

    data class DailyRecordingRef(
        val id: String,
        val status: String,
    )

    /**
     * List Daily.co recordings for a room. Status is typically "waiting" or "finished".
     */
    fun listRecordingsForRoom(roomName: String): List<DailyRecordingRef> {
        if (dailyProps.apiKey.isBlank()) return emptyList()
        val encoded = java.net.URLEncoder.encode(roomName, Charsets.UTF_8)
        val url = "${dailyProps.apiUrl}/recordings?room_name=$encoded&limit=20"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer ${dailyProps.apiKey}")
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            log.warn("Daily recordings list failed: {} - {}", response.statusCode(), response.body())
            return emptyList()
        }
        val json = mapper.readTree(response.body())
        val data = json.path("data")
        if (!data.isArray) return emptyList()
        return data.mapNotNull { node ->
            val id = node.path("id").asText("").ifBlank { node.path("recording_id").asText("") }
            if (id.isBlank()) null
            else DailyRecordingRef(id = id, status = node.path("status").asText(""))
        }
    }

    /**
     * Get download URL from Daily.co API for a recording.
     */
    fun getDailyRecordingDownloadUrl(recordingId: String): String {
        val url = "${dailyProps.apiUrl}/recordings/$recordingId/access-link"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer ${dailyProps.apiKey}")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            log.error("Daily access-link API error: {} - {}", response.statusCode(), response.body())
            throw RuntimeException("Failed to get Daily recording download URL: ${response.statusCode()}")
        }

        val json = mapper.readTree(response.body())
        val downloadLink = json.path("download_link").asText(null)
            ?: throw RuntimeException("Daily API did not return download_link")
        return downloadLink
    }

    /**
     * Download Daily.co recording by ID (fetches access link then downloads).
     */
    fun downloadDailyRecording(recordingId: String): Path {
        val downloadUrl = getDailyRecordingDownloadUrl(recordingId)
        return downloadFromUrl(downloadUrl)
    }

    /**
     * Ensure file is in a format Whisper accepts. For now we pass through - Whisper accepts mp4, wav, etc.
     */
    fun ensureAudioFormat(inputPath: Path): Path = inputPath

    /**
     * Save uploaded file to temp (for in-person flow). Returns the path.
     */
    fun saveUploadToTemp(file: File, originalFilename: String?): Path {
        val ext = originalFilename?.substringAfterLast('.')?.takeIf { it.length in 1..5 } ?: "wav"
        val tempPath = Path.of(scribeProps.tempDir, "${UUID.randomUUID()}.$ext")
        Files.copy(file.toPath(), tempPath)
        log.info("Saved upload to temp: {}", tempPath.fileName)
        return tempPath
    }

    /**
     * Delete temp file. Safe to call even if file already deleted.
     */
    fun cleanupTempFile(path: Path) {
        try {
            if (Files.exists(path)) {
                Files.delete(path)
                log.debug("Cleaned up temp file: {}", path.fileName)
            }
        } catch (e: Exception) {
            log.warn("Failed to cleanup temp file {}: {}", path.fileName, e.message)
        }
    }
}
