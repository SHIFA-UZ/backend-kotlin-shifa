// src/main/kotlin/com/shifa/web/PatientPhotoController.kt
package com.shifa.web

import com.shifa.config.AppProperties
import com.shifa.repo.PatientProfileRepository
import com.shifa.security.PatientPrincipal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.util.Locale

@RestController
@RequestMapping("/api/patients/me/photo")
class PatientPhotoController(
    private val patientProfiles: PatientProfileRepository,
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

    // -------------------- POST /api/patients/me/photo --------------------

    @PostMapping
    fun upload(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @RequestParam("file") file: MultipartFile
    ): UploadResp {

        val profile = principal.user.phone?.let { patientProfiles.findByPhone(it) }
            ?.orElseGet {
                principal.user.email?.let { patientProfiles.findByEmail(it) }
                    ?.orElse(null)
            }
            ?: throw org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "Patient profile not found"
            )

        // Build `<name-slug>-<id>.<ext>`
        val fullName = profile.fullName.ifBlank { "patient" }
        val slug = slugifyName(fullName)

        val original = file.originalFilename ?: "photo.jpg"
        val ext = original.substringAfterLast('.', "jpg").lowercase(Locale.getDefault())

        // Save under app.storageRoot (served by Nginx on 8090)
        val imagesRoot = Path.of(appProps.storageRoot)
        val patientsDir = imagesRoot.resolve("patients")
        Files.createDirectories(patientsDir)

        val profileId = profile.id ?: throw org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
            "Patient profile ID not found"
        )

        val target = patientsDir.resolve("$slug-$profileId.$ext")
        file.inputStream.use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }

        // Persist relative path in DB
        profile.photoUrl = "patients/$slug-$profileId.$ext"
        patientProfiles.save(profile)

        // Return absolute URL for client
        val absoluteUrl =
            "${appProps.publicBaseUrl.removeSuffix("/")}/${profile.photoUrl}"

        return UploadResp(photoUrl = absoluteUrl)
    }
}
