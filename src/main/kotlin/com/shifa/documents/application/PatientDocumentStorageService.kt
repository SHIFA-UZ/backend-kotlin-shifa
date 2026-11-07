
// src/main/kotlin/com/shifa/documents/application/PatientDocumentStorageService.kt
package com.shifa.documents.application

import com.shifa.shared.storage.FileStorage
import com.shifa.shared.storage.StorageKeys
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.util.Locale

@Service
class PatientDocumentStorageService(
    private val signer: com.shifa.shared.security.FileUrlSigner,
    private val uploadGuard: com.shifa.shared.UploadGuard,
    private val storage: FileStorage,
    private val publicUrls: com.shifa.shared.PublicUrls,
    private val heic: com.shifa.shared.storage.HeicConverter,
) {

    data class SaveResult(
        val filePathRelative: String,
        val publicUrl: String
    )

    fun saveDocument(
        patientId: Long,
        file: MultipartFile,
        preferredBaseName: String?,
        kind: com.shifa.shared.UploadPolicy.Kind = com.shifa.shared.UploadPolicy.Kind.DOCUMENT,
    ): SaveResult {
        uploadGuard.check(kind, file)
        val originalName = file.originalFilename ?: "document"
        // HEIC/HEIF is stored as JPEG when it can be converted (BE-21); otherwise as uploaded.
        val jpeg = if (heic.isHeic(originalName, file.contentType)) file.inputStream.use { heic.toJpeg(it) } else null
        val extension = if (jpeg != null) "jpg" else resolveExtension(file)
        val safeBase = sanitizeBaseName(preferredBaseName ?: originalName)
        // Unique suffix avoids overwriting another upload with the same display name.
        val rel = "patientdocuments/$patientId/$safeBase-${System.currentTimeMillis()}.$extension"
        if (jpeg != null) {
            storage.put(rel, ByteArrayInputStream(jpeg), jpeg.size.toLong(), "image/jpeg")
        } else {
            file.inputStream.use { storage.put(rel, it, file.size, file.contentType) }
        }
        return SaveResult(filePathRelative = rel, publicUrl = publicUrlFor(rel))
    }

    fun saveBytes(
        patientId: Long,
        bytes: ByteArray,
        preferredBaseName: String?,
        extension: String = "pdf",
    ): SaveResult {
        val ext = extension.lowercase(Locale.getDefault()).ifBlank { "pdf" }
        val safeBase = sanitizeBaseName(preferredBaseName ?: "document")
        val rel = "patientdocuments/$patientId/$safeBase-${System.currentTimeMillis()}.$ext"
        val contentType = if (ext == "pdf") "application/pdf" else null
        storage.put(rel, ByteArrayInputStream(bytes), bytes.size.toLong(), contentType)
        return SaveResult(filePathRelative = rel, publicUrl = publicUrlFor(rel))
    }

    // Backward-compatible alias.
    fun savePdf(patientId: Long, file: MultipartFile, preferredBaseName: String?): SaveResult {
        return saveDocument(patientId, file, preferredBaseName)
    }

    /** Absolute, signed URL (see [com.shifa.shared.security.FileUrlSigner]); valid for one hour. */
    fun publicUrlFor(filePathRelative: String): String {
        return signer.sign(publicUrls.absolute(filePathRelative)!!)
    }

    /** A document the server generated and filed: its id and the same signed URL `PatientDocumentDto.url` carries. */
    fun filed(document: com.shifa.documents.domain.PatientDocument): FiledDocument =
        FiledDocument(requireNotNull(document.id) { "Document must be saved" }, publicUrlFor(document.filePath))

    /** Delete the stored file by relative path. Returns true if deleted or did not exist. */
    fun deleteFile(filePathRelative: String): Boolean {
        if (filePathRelative.isBlank()) return true
        val key = filePathRelative.removePrefix("/")
        if (!StorageKeys.valid(key)) return false
        return runCatching { storage.delete(key) }.getOrDefault(false)
    }

    /**
     * Copies a removed document's file under `deleted-…`, a prefix no route serves, and returns the new key: the bytes
     * stay for the record while every URL of the old key stops working once it is deleted (BE-21).
     */
    fun retireFile(filePathRelative: String): String {
        val key = StorageKeys.require(filePathRelative.removePrefix("/"))
        val retired = "deleted-$key"
        val stored = storage.head(key) ?: return retired
        requireNotNull(storage.open(key)) { "Document file vanished" }.inputStream.use {
            storage.put(retired, it, stored.size, null)
        }
        return retired
    }

    /** The stored file for an authenticated download or AI context. Returns null if it does not exist. */
    fun getFileResource(filePathRelative: String): Resource? {
        if (filePathRelative.isBlank()) return null
        val key = filePathRelative.removePrefix("/")
        if (!StorageKeys.valid(key)) return null
        return storage.open(key)
    }

    private fun resolveExtension(file: MultipartFile): String {
        val fromName = file.originalFilename
            ?.substringAfterLast('.', "")
            ?.trim()
            ?.lowercase(Locale.getDefault())
            ?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
        if (fromName != null) return fromName

        return when (file.contentType?.lowercase(Locale.getDefault())) {
            "application/pdf" -> "pdf"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "bin"
        }
    }

    private fun sanitizeBaseName(name: String): String {
        val lower = name.lowercase(Locale.getDefault()).trim()
        val base = lower.substringBeforeLast(".")
        val sanitized = base.replace("[^a-z0-9\\-_.]+".toRegex(), "-")
            .replace("[-]{2,}".toRegex(), "-")
            .trim('-', '.')
        return if (sanitized.isNotEmpty()) sanitized else "document-${System.currentTimeMillis()}"
    }
}

/** `{documentId, url}`: a PDF the server rendered and filed as a patient document (BE-01). */
data class FiledDocument(
    val documentId: Long,
    /** Absolute signed URL, valid for one hour (as `PatientDocumentDto.url`). */
    val url: String,
)
