package com.shifa.shared.storage

import org.springframework.core.io.Resource
import java.io.InputStream

/**
 * Writes go to [primary] (the bucket); reads try the bucket first and then the old volume, so the service can switch
 * to the bucket while the copy job (StorageMigrationService) is still running. Once the volume is unmounted the
 * fallback directory is simply empty.
 */
class FallbackFileStorage(private val primary: FileStorage, private val fallback: FileStorage) : FileStorage {
    override fun put(key: String, content: InputStream, size: Long, contentType: String?) = primary.put(key, content, size, contentType)
    override fun open(key: String): Resource? = primary.open(key) ?: fallback.open(key)
    override fun head(key: String): StoredObject? = primary.head(key) ?: fallback.head(key)
    /** Volume first: a migration copy in flight re-checks the volume after its upload and removes a resurrected object. */
    override fun delete(key: String): Boolean = fallback.delete(key) and primary.delete(key)
    override fun list(prefix: String): Sequence<StoredObject> = primary.list(prefix)
}
