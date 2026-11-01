package com.shifa.shared.storage

import org.springframework.core.io.Resource
import java.io.InputStream

/**
 * Writes go to [primary] (the bucket); reads try the bucket first and then the old volume's directory. The volumes
 * were copied and deleted on 2026-11-01, so on Railway that directory is simply empty.
 */
class FallbackFileStorage(private val primary: FileStorage, private val fallback: FileStorage) : FileStorage {
    override fun put(key: String, content: InputStream, size: Long, contentType: String?) = primary.put(key, content, size, contentType)
    override fun open(key: String): Resource? = primary.open(key) ?: fallback.open(key)
    override fun head(key: String): StoredObject? = primary.head(key) ?: fallback.head(key)
    override fun delete(key: String): Boolean = fallback.delete(key) and primary.delete(key)
    override fun list(prefix: String): Sequence<StoredObject> = primary.list(prefix)
}
