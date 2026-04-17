
// src/main/kotlin/com/shifa/service/PatientDocumentStorageService.kt
package com.shifa.service

import com.shifa.config.AppProperties
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Locale

@Service
class PatientDocumentStorageService(private val app: AppProperties) {

    data class SaveResult(
        val filePathRelative: String,
        val publicUrl: String
    )

    fun savePdf(patientId: Long, file: MultipartFile, preferredBaseName: String?): SaveResult { // <-- Long
        val safeBase = sanitizeBaseName(preferredBaseName ?: (file.originalFilename ?: "document"))
        val fileName = "$safeBase.pdf"

        val base = Path.of(app.storageRoot)
        val dir = base.resolve("patientdocuments").resolve(patientId.toString())
        Files.createDirectories(dir)

        val target = dir.resolve(fileName)
        file.inputStream.use { input -> Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING) }

        val rel = "patientdocuments/$patientId/$fileName"
        val url = "${app.publicBaseUrl.removeSuffix("/")}/$rel"
        return SaveResult(filePathRelative = rel, publicUrl = url)
    }

    fun publicUrlFor(filePathRelative: String): String {
        val base = app.publicBaseUrl.removeSuffix("/")
        val rel = filePathRelative.removePrefix("/")
        return "$base/$rel"
    }

    /** Delete file on disk by relative path. Returns true if deleted or did not exist. */
    fun deleteFile(filePathRelative: String): Boolean {
        if (filePathRelative.isBlank()) return true
        val base = Paths.get(app.storageRoot).toAbsolutePath().normalize()
        val fullPath = base.resolve(filePathRelative.removePrefix("/")).normalize()
        if (!fullPath.startsWith(base)) return false
        return try {
            Files.deleteIfExists(fullPath)
        } catch (_: Exception) {
            false
        }
    }

    /** Resolve file on disk for authenticated download. Returns null if file does not exist. */
    fun getFileResource(filePathRelative: String): Resource? {
        if (filePathRelative.isBlank()) return null
        val base = Paths.get(app.storageRoot).toAbsolutePath().normalize()
        val fullPath = base.resolve(filePathRelative.removePrefix("/")).normalize()
        if (!fullPath.startsWith(base) || !Files.isRegularFile(fullPath)) return null
        return FileSystemResource(fullPath.toFile())
    }

    private fun sanitizeBaseName(name: String): String {
        val lower = name.lowercase(Locale.getDefault()).trim()
        val base = lower.substringBeforeLast(".")
        return base.replace("[^a-z0-9\\-_.]+".toRegex(), "-")
            .replace("[-]{2,}".toRegex(), "-")
            .trim('-', '.')
    }
}
