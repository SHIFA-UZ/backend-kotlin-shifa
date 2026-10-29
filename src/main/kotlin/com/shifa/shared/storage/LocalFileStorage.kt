package com.shifa.shared.storage

import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

/** Files under [root]; the pre-2.7 layout, still used locally, in tests and as the read fallback during the copy to the bucket. */
class LocalFileStorage(root: Path) : FileStorage {
    private val root: Path = Files.createDirectories(root.toAbsolutePath().normalize()).toRealPath()

    /** Lexically inside the root, and, once something exists there, also physically (no symlink pointing outside). */
    private fun path(key: String): Path {
        val resolved = root.resolve(StorageKeys.require(key)).normalize()
        require(resolved.startsWith(root)) { "Invalid storage key" }
        var existing = resolved
        while (!Files.exists(existing)) existing = existing.parent
        require(existing.toRealPath().startsWith(root)) { "Invalid storage key" }
        return resolved
    }

    override fun put(key: String, content: InputStream, size: Long, contentType: String?) {
        val target = path(key)
        Files.createDirectories(target.parent)
        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun open(key: String): Resource? = path(key).takeIf { it.isRegularFile() }?.let { FileSystemResource(it) }

    override fun head(key: String): StoredObject? = path(key).takeIf { it.isRegularFile() }?.let { StoredObject(key, it.fileSize()) }

    override fun delete(key: String): Boolean = Files.deleteIfExists(path(key)) || true

    override fun list(prefix: String): Sequence<StoredObject> {
        val start = if (prefix.isEmpty()) root else path(prefix.removeSuffix("/"))
        if (!Files.isDirectory(start)) return emptySequence()
        return sequence {
            Files.walk(start).use { walk ->
                yieldAll(
                    walk.asSequence()
                        .filter { it.isRegularFile() }
                        .map { StoredObject(root.relativize(it).toString().replace('\\', '/'), it.fileSize()) },
                )
            }
        }
    }
}
