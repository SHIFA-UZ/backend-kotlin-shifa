
package com.shifa.service

import com.shifa.config.AppProperties
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*

@Service
class DoctorPhotoStorageService(
    private val appProperties: AppProperties
) {
    /**
     * Saves the uploaded photo to {storageRoot}/doctors/{slugOrId}.{ext}
     * and returns the public URL: {publicBaseUrl}/doctors/{slugOrId}.{ext}
     */
    fun saveDoctorPhoto(slugOrId: String, file: MultipartFile): String {
        val ext = guessExtension(file.originalFilename)
        val fileName = "$slugOrId.$ext"

        val doctorsDir = Path.of(appProperties.storageRoot, "doctors")
        Files.createDirectories(doctorsDir)

        val target = doctorsDir.resolve(fileName)
        file.inputStream.use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }

        return "${appProperties.publicBaseUrl}/doctors/$fileName"
    }

    private fun guessExtension(original: String?): String {
        val lower = (original ?: "").lowercase(Locale.getDefault())
        return when {
            lower.endsWith(".jpeg") -> "jpeg"
            lower.endsWith(".jpg")  -> "jpg"
            lower.endsWith(".png")  -> "png"
            lower.endsWith(".webp") -> "webp"
            else -> "jpg" // default fallback
        }
    }
}
