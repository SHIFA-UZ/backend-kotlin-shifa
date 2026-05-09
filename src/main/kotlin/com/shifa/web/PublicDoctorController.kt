// src/main/kotlin/com/shifa/web/PublicDoctorController.kt
package com.shifa.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.config.AppProperties
import com.shifa.domain.DoctorProfile
import com.shifa.repo.DoctorServicePriceRepository
import com.shifa.repo.DoctorServiceRepository
import com.shifa.repo.DoctorLocationRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.DoctorReviewRepository
import com.shifa.security.PatientPrincipal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/public/doctors")
class PublicDoctorController(
    private val doctorProfiles: DoctorProfileRepository,
    private val reviewRepository: DoctorReviewRepository,
    private val doctorLocations: DoctorLocationRepository,
    private val doctorServices: DoctorServiceRepository,
    private val doctorServicePrices: DoctorServicePriceRepository,
    private val appProps: AppProperties,
    private val objectMapper: ObjectMapper
) {
    data class ServicePriceDto(
        val amountMinor: Long,
        val currency: String
    )

    data class ServiceDto(
        val id: Long,
        val title: String,
        val description: String?,
        /** True when this video-bookable service is free (no patient payment). */
        val isFreeConsultation: Boolean = false,
        val prices: List<ServicePriceDto>
    )


    data class PublicLocationDto(
        val id: Long,
        val label: String,
        val clinic: String?,
        val address: String?,
        val latitude: Double?,
        val longitude: Double?,
        val locationCountry: String?,
        val locationRegion: String?,
        val locationCity: String?,
        val locationStreetAddress: String?,
        val isPrimary: Boolean
    )

    data class DoctorDto(
        val id: Long?,
        val firstName: String,
        val lastName: String,
        val fullName: String,
        val profession: String?,
        val clinic: String?,
        val address: String?,
        val phone: String?,
        val email: String?,
        val photoUrl: String?,
        val averageRating: Double?,
        val reviewCount: Long,
        val biography: String?,
        val services: List<String>?,
        val serviceItems: List<ServiceDto>?,
        val certificates: List<String>?,
        val telegram: String?,
        val instagram: String?,
        val specializations: List<String>?,
        val furtherInformation: String?,
        val latitude: Double?,
        val longitude: Double?,
        val distanceKm: Double? = null, // Distance in kilometers if user location provided
        val locationRegion: String? = null,
        val locationCity: String? = null,
        val locationStreetAddress: String? = null
    )

    private fun normalizeAvatarUrl(avatarUrl: String?): String? {
        val trimmed = avatarUrl?.trim() ?: return null
        val isAbs = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        return if (isAbs) trimmed
        else "${appProps.publicBaseUrl.removeSuffix("/")}/${trimmed.removePrefix("/")}"
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

    /**
     * Calculate distance between two coordinates using Haversine formula
     * Returns distance in kilometers
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadiusKm * c
    }

    /**
     * GET /api/public/doctors
     * List all available doctors (for patients to search/browse)
     * 
     * Query parameters:
     * - search: Search by name, profession, or clinic
     * - profession: Filter by profession
     * - clinic: Filter by clinic
     * - latitude: User's latitude for distance calculation
     * - longitude: User's longitude for distance calculation
     * - radiusKm: Maximum distance in kilometers (default: 50km)
     * - sortBy: Sort by 'distance' or 'rating' (default: no sorting)
     */
    @GetMapping
    fun listDoctors(
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) profession: String?,
        @RequestParam(required = false) clinic: String?,
        @RequestParam(required = false) latitude: Double?,
        @RequestParam(required = false) longitude: Double?,
        @RequestParam(required = false, defaultValue = "50") radiusKm: Double?,
        @RequestParam(required = false) sortBy: String?
    ): List<DoctorDto> {
        // Use database query to filter at database level instead of loading all records
        // This prevents OutOfMemoryError when database grows large
        var doctors = if (!search.isNullOrBlank() || !profession.isNullOrBlank()) {
            doctorProfiles.searchWithFilters(search, profession)
        } else {
            // Only enabled (non-disabled) doctors for public listing
            doctorProfiles.findAllByUserEnabled()
        }

        // Filter by clinic (if not already filtered by database query)
        if (!clinic.isNullOrBlank()) {
            doctors = doctors.filter { it.clinic?.equals(clinic, ignoreCase = true) == true }
        }

        // Calculate distances and filter by radius if user location provided
        val doctorsWithDistance = doctors.map { doc ->
            val distance = if (latitude != null && longitude != null && 
                              doc.latitude != null && doc.longitude != null) {
                calculateDistance(latitude, longitude, doc.latitude!!, doc.longitude!!)
            } else null

            Pair(doc, distance)
        }

        // Filter by radius
        val filteredDoctors = if (latitude != null && longitude != null && radiusKm != null) {
            doctorsWithDistance.filter { (_, dist) ->
                dist == null || dist <= radiusKm
            }
        } else {
            doctorsWithDistance
        }

        // Sort by distance or rating
        val sortedDoctors = when (sortBy?.lowercase()) {
            "distance" -> {
                if (latitude != null && longitude != null) {
                    filteredDoctors.sortedBy { (_, dist) -> dist ?: Double.MAX_VALUE }
                } else {
                    filteredDoctors
                }
            }
            "rating" -> {
                filteredDoctors.sortedByDescending { (doc, _) ->
                    reviewRepository.findAverageRatingByDoctorId(doc.id!!) ?: 0.0
                }
            }
            else -> filteredDoctors
        }

        return sortedDoctors.map { (doc, distance) ->
            val avgRating = reviewRepository.findAverageRatingByDoctorId(doc.id!!)
            val reviewCount = reviewRepository.countByDoctorId(doc.id!!)
            
            val certList = parseJsonList(doc.certificates)
            val normalizedCerts: List<String>? = certList?.mapNotNull { normalizeCertificateUrl(it) }
            
            DoctorDto(
                id = doc.id,
                firstName = doc.firstName,
                lastName = doc.lastName,
                fullName = "${doc.firstName} ${doc.lastName}".trim(),
                profession = doc.profession,
                clinic = doc.clinic,
                address = doc.address,
                phone = doc.user.phone,
                email = doc.user.email,
                photoUrl = normalizeAvatarUrl(doc.avatarUrl),
                averageRating = avgRating,
                reviewCount = reviewCount,
                biography = doc.biography,
                services = parseJsonList(doc.services),
                serviceItems = mapServiceItems(doc.id!!),
                certificates = normalizedCerts,
                telegram = doc.telegram,
                instagram = doc.instagram,
                specializations = null, // TODO: Add specializations field if needed
                furtherInformation = doc.biography, // Using biography as furtherInformation for now
                latitude = doc.latitude,
                longitude = doc.longitude,
                distanceKm = distance,
                locationRegion = doc.locationRegion,
                locationCity = doc.locationCity,
                locationStreetAddress = doc.locationStreetAddress
            )
        }
    }

    /**
     * GET /api/public/doctors/{id}
     * Get doctor details by ID
     */
    @GetMapping("/{id}")
    fun getDoctor(@PathVariable id: Long): DoctorDto {
        val doctor = doctorProfiles.findById(id)
            .orElseThrow { org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "Doctor not found"
            ) }
        // Do not expose disabled doctors to patients
        if (!doctor.user.enabled) {
            throw org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "Doctor not found"
            )
        }

        val avgRating = reviewRepository.findAverageRatingByDoctorId(doctor.id!!)
        val reviewCount = reviewRepository.countByDoctorId(doctor.id!!)
        
        val certList = parseJsonList(doctor.certificates)
        val normalizedCerts: List<String>? = certList?.mapNotNull { normalizeCertificateUrl(it) }
        
        return DoctorDto(
            id = doctor.id,
            firstName = doctor.firstName,
            lastName = doctor.lastName,
            fullName = "${doctor.firstName} ${doctor.lastName}".trim(),
            profession = doctor.profession,
            clinic = doctor.clinic,
            address = doctor.address,
            phone = doctor.user.phone,
            email = doctor.user.email,
            photoUrl = normalizeAvatarUrl(doctor.avatarUrl),
            averageRating = avgRating,
            reviewCount = reviewCount,
            biography = doctor.biography,
            services = parseJsonList(doctor.services),
            serviceItems = mapServiceItems(doctor.id!!),
            certificates = normalizedCerts,
            telegram = doctor.telegram,
            instagram = doctor.instagram,
            specializations = null, // TODO: Add specializations field if needed
            furtherInformation = doctor.biography, // Using biography as furtherInformation for now
            latitude = doctor.latitude,
            longitude = doctor.longitude,
            distanceKm = null,
            locationRegion = doctor.locationRegion,
            locationCity = doctor.locationCity,
            locationStreetAddress = doctor.locationStreetAddress
        )
    }

    private fun mapServiceItems(doctorId: Long): List<ServiceDto> {
        return doctorServices.findByDoctorIdAndIsActiveTrueOrderByCreatedAtAsc(doctorId).map { s ->
            val priceItems = doctorServicePrices.findByService_IdOrderByCurrencyAsc(s.id).map {
                ServicePriceDto(amountMinor = it.amountMinor, currency = it.currency)
            }
            ServiceDto(
                id = s.id,
                title = s.title,
                description = s.description,
                isFreeConsultation = s.isFreeConsultation,
                prices = priceItems
            )
        }
    }

    /**
     * GET /api/public/doctors/{id}/locations
     *
     * Returns the list of practice locations for a doctor. Used by the patient app to let the
     * patient pick which clinic they want to book before seeing time slots for that location.
     * For doctors who haven't set up multi-location yet, we synthesize a single entry from the
     * legacy profile fields so clients can uniformly handle the one-location case.
     */
    @GetMapping("/{id}/locations")
    fun getDoctorLocations(@PathVariable id: Long): List<PublicLocationDto> {
        val doctor = doctorProfiles.findById(id)
            .orElseThrow { org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "Doctor not found"
            ) }
        if (!doctor.user.enabled) {
            throw org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "Doctor not found"
            )
        }

        val locations = doctorLocations.findByDoctorIdOrderByIsPrimaryDescIdAsc(doctor.id!!)
        if (locations.isNotEmpty()) {
            return locations.map { loc ->
                PublicLocationDto(
                    id = loc.id!!,
                    label = loc.label,
                    clinic = loc.clinic,
                    address = loc.address,
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    locationCountry = loc.locationCountry,
                    locationRegion = loc.locationRegion,
                    locationCity = loc.locationCity,
                    locationStreetAddress = loc.locationStreetAddress,
                    isPrimary = loc.isPrimary
                )
            }
        }

        // Legacy fallback: synthesize a single pseudo-location from the profile.
        val hasAnyLocationData = !doctor.clinic.isNullOrBlank() ||
            !doctor.address.isNullOrBlank() ||
            doctor.latitude != null ||
            doctor.longitude != null ||
            !doctor.locationCity.isNullOrBlank()
        if (!hasAnyLocationData) return emptyList()

        return listOf(
            PublicLocationDto(
                id = -1L, // sentinel; clients should treat <=0 as "doctor has no structured location"
                label = doctor.clinic ?: "Main Clinic",
                clinic = doctor.clinic,
                address = doctor.address,
                latitude = doctor.latitude,
                longitude = doctor.longitude,
                locationCountry = doctor.locationCountry,
                locationRegion = doctor.locationRegion,
                locationCity = doctor.locationCity,
                locationStreetAddress = doctor.locationStreetAddress,
                isPrimary = true
            )
        )
    }
}
