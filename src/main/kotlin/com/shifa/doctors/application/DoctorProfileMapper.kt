
package com.shifa.doctors.application

import tools.jackson.databind.ObjectMapper
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.doctors.api.DoctorProfileDto
import org.springframework.stereotype.Component

@Component
class DoctorProfileMapper(
    private val objectMapper: ObjectMapper,
    private val publicUrls: com.shifa.shared.PublicUrls,
) {
    /** Make avatarUrl absolute using app.publicBaseUrl. */
    fun normalizeAvatarUrl(avatarUrl: String?): String? = publicUrls.absolute(avatarUrl)

    private fun parseJsonList(jsonString: String?): List<String>? {
        if (jsonString.isNullOrBlank()) return null
        return try {
            objectMapper.readValue(jsonString, Array<String>::class.java).toList()
        } catch (e: Exception) {
            null
        }
    }

    private fun normalizeCertificateUrl(certUrl: String?): String? = publicUrls.absolute(certUrl)

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