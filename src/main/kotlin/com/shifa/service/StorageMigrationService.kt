package com.shifa.service

import com.shifa.config.AppProperties
import com.shifa.shared.storage.FileStorage
import com.shifa.shared.storage.LocalFileStorage
import com.shifa.shared.storage.S3FileStorage
import com.shifa.shared.storage.StoredObject
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.http.MediaTypeFactory
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-time copy of the volume's files into the bucket (roadmap 2.7), started by an admin through
 * [com.shifa.web.StorageMigrationController]. The bucket wins: an object that already exists there is never
 * overwritten (a photo re-uploaded after the switch must not be replaced by its volume copy), so the job can be
 * re-run until it reports zero copied and zero failed. Runs on dev first, then on production while the volume is
 * still mounted; the volume is unmounted only after a week of zero fallback reads.
 */
@Service
class StorageMigrationService(app: AppProperties, private val s3: ObjectProvider<S3FileStorage>) {
    private val log = LoggerFactory.getLogger(StorageMigrationService::class.java)

    data class Status(
        val state: String,
        val dryRun: Boolean = false,
        val scanned: Int = 0,
        val copied: Int = 0,
        val skipped: Int = 0,
        /** Present in both places with different sizes; the bucket copy was kept. Listed in the log. */
        val conflicts: Int = 0,
        val failed: Int = 0,
        val bytes: Long = 0,
        val startedAt: Instant? = null,
        val finishedAt: Instant? = null,
        val lastError: String? = null,
    )

    private val root = Path.of(app.storageRoot).toAbsolutePath().normalize()
    /** The served tree, plus the private sibling where transcription feedback used to be written. */
    private val sources = listOf("" to LocalFileStorage(root), "private/" to LocalFileStorage(root.resolveSibling("private")))
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "storage-migration").apply { isDaemon = true } }

    // ponytail: in-memory status, one instance; fine for a job run by hand a handful of times.
    @Volatile
    private var current = Status(state = "idle")
    val status: Status get() = current

    fun start(dryRun: Boolean): Status {
        val target = s3.ifAvailable ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Object storage is not enabled")
        if (!running.compareAndSet(false, true)) throw ResponseStatusException(HttpStatus.CONFLICT, "Migration already running")
        current = Status(state = "running", dryRun = dryRun, startedAt = Instant.now())
        executor.execute {
            try {
                runOnce(target, dryRun)
            } finally {
                running.set(false)
            }
        }
        return current
    }

    /** The whole copy, synchronously; [start] runs it on the background thread, tests call it directly. */
    internal fun runOnce(target: FileStorage, dryRun: Boolean): Status {
        var s = Status(state = "running", dryRun = dryRun, startedAt = current.startedAt ?: Instant.now())
        try {
            val objects = sources.asSequence().flatMap { (prefix, source) -> source.list("").map { Triple(source, it, prefix + it.key) } }
            for ((source, obj, key) in objects) {
                s = migrateOne(s, source, target, obj, key, dryRun)
                current = s
            }
            s = s.copy(state = "done", finishedAt = Instant.now())
        } catch (e: Exception) {
            s = s.copy(state = "failed", finishedAt = Instant.now(), lastError = e.toString().take(300))
        }
        current = s
        log.info(
            "Storage migration {}: scanned={} copied={} skipped={} conflicts={} failed={} bytes={}",
            s.state, s.scanned, s.copied, s.skipped, s.conflicts, s.failed, s.bytes,
        )
        return s
    }

    private fun migrateOne(s: Status, source: FileStorage, target: FileStorage, obj: StoredObject, key: String, dryRun: Boolean): Status {
        val scanned = s.copy(scanned = s.scanned + 1)
        return try {
            val existing = target.head(key)
            when {
                existing == null -> {
                    if (!dryRun) copy(source, target, obj.key, key, obj.size)
                    scanned.copy(copied = scanned.copied + 1, bytes = scanned.bytes + obj.size)
                }
                existing.size == obj.size -> scanned.copy(skipped = scanned.skipped + 1)
                else -> {
                    log.warn(
                        "Storage migration: {} differs in the bucket ({} vs {} bytes); bucket copy kept",
                        key.substringBeforeLast('/', ""), existing.size, obj.size,
                    )
                    scanned.copy(conflicts = scanned.conflicts + 1)
                }
            }
        } catch (e: Exception) {
            log.warn("Storage migration: {} failed: {}", key.substringBeforeLast('/', ""), e.toString())
            scanned.copy(failed = scanned.failed + 1, lastError = e.toString().take(300))
        }
    }

    private fun copy(source: FileStorage, target: FileStorage, sourceKey: String, targetKey: String, size: Long) {
        val resource = checkNotNull(source.open(sourceKey)) { "vanished before copy" }
        val contentType = MediaTypeFactory.getMediaType(targetKey.substringAfterLast('/')).map { it.toString() }.orElse(null)
        resource.inputStream.use { target.put(targetKey, it, size, contentType) }
        if (source.head(sourceKey) == null) {
            // Deleted on the volume while we were copying (FallbackFileStorage deletes both sides): do not resurrect it.
            target.delete(targetKey)
            error("deleted during copy; bucket copy removed again")
        }
        val stored = checkNotNull(target.head(targetKey)) { "not found after upload" }
        check(stored.size == size) { "size mismatch after upload: ${stored.size} != $size" }
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }
}
