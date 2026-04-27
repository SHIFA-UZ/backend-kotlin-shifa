
package com.shifa.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.config.AppProperties
import com.shifa.domain.DoctorProfile
import com.shifa.web.dto.DoctorProfileDto
import org.springframework.stereotype.Component

@Component
class DoctorProfileMapper(
    private val appProps: AppProperties,
    private val objectMapper: ObjectMapper
) {
    /** Make avatarUrl absolute using app.publicBaseUrl (e.g., http://localhost:8090). */
    fun normalizeAvatarUrl(avatarUrl: String?): String? {
        val trimmed = avatarUrl?.trim()?.replace("\\", "/")
        if (trimmed.isNullOrEmpty()) return null
        
        val isAbsolute = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        if (isAbsolute) return trimmed
        
        val base = appProps.publicBaseUrl.removeSuffix("/")
        val path = trimmed.removePrefix("/")
        return "$base/$path"
    }

    private fun parseJsonList(jsonString: String?): List<String>? {
        if (jsonString.isNullOrBlank()) return null
        return try {
            objectMapper.readValue(jsonString, Array<String>::class.java).toList()
        } catch (e: Exception) {
            null
        }
    }

    private fun normalizeCertificateUrl(certUrl: String?): String? {
        if (certUrl.isNullOrBlank()) return null
        val trimmed = certUrl.trim()
        val isAbs = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        return if (isAbs) trimmed
        else "${appProps.publicBaseUrl.removeSuffix("/")}/${trimmed.removePrefix("/")}"
    }

    fun toDto(d: DoctorProfile): DoctorProfileDto {
        val fullName = "${d.firstName} ${d.lastName}".trim()
        val certList = parseJsonList(d.certificates)
        val normalizedCerts: List<String>? = certList?.mapNotNull { normalizeCertificateUrl(it) }
        
        return DoctorProfileDto(
            id = d.id,
            firstName = d.firstName,
            lastName = d.lastName,
            fullName = fullName,
            dob = d.dob?.toString(),
            gender = d.gender,
            address = d.address,
            clinic = d.clinic,
            profession = d.profession,
            photoUrl = normalizeAvatarUrl(d.avatarUrl),
            biography = d.biography,
            services = parseJsonList(d.services),
            certificates = normalizedCerts,
            telegram = d.telegram,
            instagram = d.instagram,
            latitude = d.latitude,
            longitude = d.longitude,
            locationCountry = d.locationCountry,
            locationRegion = d.locationRegion,
            locationDistrict = d.locationDistrict,
            locationCity = d.locationCity,
            locationPostalCode = d.locationPostalCode,
            locationStreetAddress = d.locationStreetAddress,
            consultationPriceMinor = d.consultationPriceMinor,
            consultationCurrency = d.consultationCurrency
        )
    }
}