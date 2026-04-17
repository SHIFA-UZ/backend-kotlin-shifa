
// src/main/kotlin/com/shifa/service/PatientProfileMapper.kt
package com.shifa.service

import com.shifa.config.AppProperties
import com.shifa.domain.PatientProfile
import com.shifa.web.dto.PatientProfileDto
import org.springframework.stereotype.Component

@Component
class PatientProfileMapper(
    private val appProperties: AppProperties
) {
    /** Returns an absolute URL: if DB has a relative path, prepend app.publicBaseUrl (e.g., http://localhost:8090). */
    fun normalizePhotoUrl(photoUrl: String?): String? {
        val trimmed = photoUrl?.trim()?.replace("\\", "/")
        if (trimmed.isNullOrEmpty()) return null

        val isAbsolute = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        if (isAbsolute) return trimmed
        
        // Handle common path issues
        val cleanPath = trimmed.removePrefix("/")
        
        val base = appProperties.publicBaseUrl.removeSuffix("/")
        return "$base/$cleanPath"
    }

    /** Optional: DTO helper, if you want to reuse it for simple mapping. */
    fun toDto(profile: PatientProfile): PatientProfileDto {
        return PatientProfileDto(
            id = profile.id!!,
            fullName = profile.fullName,
            phone = profile.phone,
            photoUrl = normalizePhotoUrl(profile.photoUrl)
        )
    }
}
