// src/main/kotlin/com/shifa/web/ProfilePhotoController.kt
package com.shifa.web

import com.shifa.config.AppProperties
import com.shifa.repo.DoctorProfileRepository
import com.shifa.security.DoctorPrincipal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.util.Locale

@RestController
@RequestMapping("/api/profile/photo")
class ProfilePhotoController(
    private val doctors: DoctorProfileRepository,
    private val appProps: AppProperties
) {

    data class UploadResp(val photoUrl: String)

    // -------------------- helpers --------------------

    private fun slugifyName(name: String): String {
        val base = name.lowercase(Locale.getDefault()).trim()
        val norm = Normalizer.normalize(base, Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        return norm.replace("[^a-z0-9]+".toRegex(), "-")
            .replace("[-]{2,}".toRegex(), "-")
            .trim('-')
    }

    // -------------------- POST /api/profile/photo --------------------

    @PostMapping
    fun upload(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestParam("file") file: MultipartFile
    ): UploadResp {

        val doctor = principal.profile

        // Build `<name-slug>-<id>.<ext>`
        val fullName = "${doctor.firstName} ${doctor.lastName}".trim()
        val slug = slugifyName(fullName.ifBlank { "doctor" })

        val original = file.originalFilename ?: "photo.jpg"
        val ext = original.substringAfterLast('.', "jpg").lowercase(Locale.getDefault())

        // Save under app.storageRoot (served by Nginx on 8090)
        val imagesRoot = Path.of(appProps.storageRoot)
        val doctorsDir = imagesRoot.resolve("doctors")
        Files.createDirectories(doctorsDir)

        val target = doctorsDir.resolve("$slug-${doctor.id}.$ext")
        file.inputStream.use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }

        // Persist relative path in DB
        doctor.avatarUrl = "doctors/$slug-${doctor.id}.$ext"
        doctors.save(doctor)

        // Return absolute URL for client
        val absoluteUrl =
            "${appProps.publicBaseUrl.removeSuffix("/")}/${doctor.avatarUrl}"

        return UploadResp(photoUrl = absoluteUrl)
    }
}
