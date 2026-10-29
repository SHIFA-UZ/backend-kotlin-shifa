// src/main/kotlin/com/shifa/doctors/api/ProfilePhotoController.kt
package com.shifa.doctors.api

import com.shifa.shared.security.DoctorPrincipal
import java.text.Normalizer
import java.util.Locale
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/profile/photo")
class ProfilePhotoController(
    private val doctors: com.shifa.doctors.application.DoctorProfiles,
    private val uploadGuard: com.shifa.shared.UploadGuard,
    private val storage: com.shifa.shared.storage.FileStorage,
    private val publicUrls: com.shifa.shared.PublicUrls,
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
        uploadGuard.check(com.shifa.shared.UploadPolicy.Kind.IMAGE, file)

        // Build `<name-slug>-<id>.<ext>`
        val fullName = "${doctor.firstName} ${doctor.lastName}".trim()
        val slug = slugifyName(fullName.ifBlank { "doctor" })

        val original = file.originalFilename ?: "photo.jpg"
        val ext = original.substringAfterLast('.', "jpg").lowercase(Locale.getDefault())

        val key = "doctors/$slug-${doctor.id}.$ext"
        file.inputStream.use { storage.put(key, it, file.size, file.contentType) }

        // Persist relative path in DB
        doctor.avatarUrl = key
        doctors.save(doctor)

        // Return absolute URL for client
        val absoluteUrl =
            publicUrls.absolute(doctor.avatarUrl)!!

        return UploadResp(photoUrl = absoluteUrl)
    }
}
