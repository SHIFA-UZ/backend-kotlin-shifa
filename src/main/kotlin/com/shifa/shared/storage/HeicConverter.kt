package com.shifa.shared.storage

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * HEIC/HEIF photos (iPhone, some Android cameras) become JPEG on upload (BE-21), so every client can preview them.
 * Uses libheif's `heif-convert` (Dockerfile: `libheif-tools`). When the tool is missing or fails, [toJpeg] answers
 * null and the caller stores the original file as before.
 */
@Component
class HeicConverter {
    private val log = LoggerFactory.getLogger(HeicConverter::class.java)

    fun isHeic(fileName: String?, contentType: String?): Boolean {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        return ext in EXTENSIONS || type in TYPES
    }

    /** The photo as JPEG bytes, or null when it can't be converted here. */
    fun toJpeg(input: InputStream): ByteArray? {
        val dir = Files.createTempDirectory("heic")
        return try {
            val source = dir.resolve("in.heic")
            Files.copy(input, source)
            val process = ProcessBuilder(BINARY, "-q", QUALITY, source.toString(), dir.resolve("out.jpg").toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            // A file with several images (live photo, burst) is written as out-1.jpg, out-2.jpg…: the first is the photo.
            val output = Files.list(dir).use { files ->
                files.filter { it.fileName.toString().let { n -> n.startsWith("out") && n.endsWith(".jpg") } }.sorted().findFirst().orElse(null)
            }
            output?.takeIf { finished && process.exitValue() == 0 }?.let(Files::readAllBytes)
        } catch (e: IOException) {
            log.warn("HEIC conversion unavailable, keeping the original: {}", e.javaClass.simpleName)
            null
        } finally {
            deleteQuietly(dir)
        }
    }

    private fun deleteQuietly(dir: Path) {
        runCatching { Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }

    companion object {
        private const val BINARY = "heif-convert"
        private const val QUALITY = "90"
        private const val TIMEOUT_SECONDS = 30L
        private val EXTENSIONS = setOf("heic", "heif")
        private val TYPES = setOf("image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence")
    }
}
