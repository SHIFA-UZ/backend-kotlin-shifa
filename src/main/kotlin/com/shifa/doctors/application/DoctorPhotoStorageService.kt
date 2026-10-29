
package com.shifa.doctors.application

import com.shifa.shared.config.AppProperties
import com.shifa.shared.storage.FileStorage
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.util.*

@Service
class DoctorPhotoStorageService(
    private val appProperties: AppProperties,
    private val storage: FileStorage,
) {
    /**
     * Saves the uploaded photo to {storageRoot}/doctors/{slugOrId}.{ext}
     * and returns the public URL: {publicBaseUrl}/doctors/{slugOrId}.{ext}
     */
    fun saveDoctorPhoto(slugOrId: String, file: MultipartFile): String {
        val ext = guessExtension(file.originalFilename)
        val fileName = "$slugOrId.$ext"

        file.inputStream.use { storage.put("doctors/$fileName", it, file.size, file.contentType) }

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
