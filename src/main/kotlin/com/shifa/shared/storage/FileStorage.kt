package com.shifa.shared.storage

import org.springframework.core.io.Resource
import java.io.InputStream

/**
 * Where uploaded files live: a directory (local, tests) or an S3-compatible bucket (Railway, roadmap 2.7).
 *
 * A key is the path the apps already know, without the leading slash: "patientdocuments/42/report.pdf",
 * "doctors/jane-doe-7.jpg", "chat-attachments/<uuid>.m4a". [StoredFileController] serves "/" + key, so moving the
 * bytes between implementations changes no URL.
 */
interface FileStorage {
    /** Stores [content] under [key], replacing an existing object. */
    fun put(key: String, content: InputStream, size: Long, contentType: String?)

    /**
     * Stores [content] only if nothing is under [key] yet; false when something was. Atomic on the bucket
     * (conditional write); the directory implementation checks then writes, which is enough for its local uses.
     */
    fun putIfAbsent(key: String, content: InputStream, size: Long, contentType: String?): Boolean {
        if (head(key) != null) return false
        put(key, content, size, contentType)
        return true
    }

    /** The stored object, or null when there is none. For a bucket the resource can be read once. */
    fun open(key: String): Resource?

    /** Size and key of the object, or null when there is none. */
    fun head(key: String): StoredObject?

    fun exists(key: String): Boolean = head(key) != null

    /** True when the object is gone afterwards (deleted now, or never there). */
    fun delete(key: String): Boolean

    /** Every object under [prefix] ("" for all). */
    fun list(prefix: String): Sequence<StoredObject>
}

data class StoredObject(val key: String, val size: Long)

/** Keys are relative paths; anything that could escape the storage root is refused before it reaches a backend. */
object StorageKeys {
    private val forbiddenSegments = setOf("", ".", "..")

    fun valid(key: String): Boolean {
        if (key.isBlank() || key.startsWith("/")) return false
        if (key.contains('\\') || key.any { it < ' ' }) return false
        return key.split('/').none { it in forbiddenSegments }
    }

    fun require(key: String): String {
        require(valid(key)) { "Invalid storage key" } // never echo the key: it may contain a patient's file name
        return key
    }
}
