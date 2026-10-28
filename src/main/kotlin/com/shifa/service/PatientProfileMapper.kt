
// src/main/kotlin/com/shifa/service/PatientProfileMapper.kt
package com.shifa.service

import com.shifa.config.AppProperties
import com.shifa.domain.PatientProfile
import com.shifa.web.dto.PatientProfileDto
import org.springframework.stereotype.Component

@Component
class PatientProfileMapper(
    private val appProperties: AppProperties,
    private val signer: com.shifa.security.FileUrlSigner,
) {
    /**
     * Returns an absolute URL: if DB has a relative path, prepend app.publicBaseUrl.
     *
     * A patient photo is signed only when [ownerProfileId] is given and the file name is the one
     * PatientPhotoController writes for that profile (`<slug>-<profileId>.<ext>`). `photoUrl` is free text a
     * client may PATCH, so without this check anyone could obtain a signature for another patient's photo
     * or document. Anything else (doctor avatars, external URLs, legacy names) passes through unsigned.
     */
    fun normalizePhotoUrl(photoUrl: String?, ownerProfileId: Long? = null): String? {
        val trimmed = photoUrl?.trim()?.replace("\\", "/")
        if (trimmed.isNullOrEmpty()) return null

        val isAbsolute = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        val absolute = if (isAbsolute) trimmed else "${appProperties.publicBaseUrl.removeSuffix("/")}/${trimmed.removePrefix("/")}"
        if (ownerProfileId == null || !isOwnPhoto(absolute, ownerProfileId)) return absolute
        return signer.sign(absolute, PHOTO_PREFIX)
    }

    /** `patients/<slug>-<profileId>.<ext>` as PatientPhotoController writes it; the slug may be empty (non-Latin names). */
    private fun isOwnPhoto(url: String, profileId: Long): Boolean {
        val path = runCatching { java.net.URI(url).path }.getOrNull() ?: return false
        val fileName = path.substringAfterLast('/')
        return path.substringBeforeLast('/').endsWith("/patients") &&
            Regex("^[^/]*-$profileId\\.[A-Za-z0-9]+$").matches(fileName)
    }

    private companion object {
        val PHOTO_PREFIX = setOf(com.shifa.security.FileUrlSigner.PATIENT_PHOTOS)
    }

    /** Optional: DTO helper, if you want to reuse it for simple mapping. */
    fun toDto(profile: PatientProfile): PatientProfileDto {
        return PatientProfileDto(
            id = profile.id!!,
            fullName = profile.fullName,
            phone = profile.phone,
            photoUrl = normalizePhotoUrl(profile.photoUrl, profile.id)
        )
    }
}
