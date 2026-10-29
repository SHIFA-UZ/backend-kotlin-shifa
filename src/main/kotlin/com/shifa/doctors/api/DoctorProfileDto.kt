
// src/main/kotlin/com/shifa/doctors/api/DoctorProfileDto.kt
package com.shifa.doctors.api

data class DoctorProfileDto(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val fullName: String,
    val dob: String?,          // yyyy-MM-dd
    val gender: String?,
    val address: String?,
    val clinic: String?,
    val profession: String?,
    val photoUrl: String?,      // absolute URL from avatarUrl
    val biography: String?,
    val services: List<String>?,
    val certificates: List<String>?,
    val telegram: String?,
    val instagram: String?,
    val latitude: Double?,
    val longitude: Double?,
    // Structured location fields
    val locationCountry: String?,
    val locationRegion: String?,
    val locationDistrict: String?,
    val locationCity: String?,
    val locationPostalCode: String?,
    val locationStreetAddress: String?,
    val consultationPriceMinor: Long?,
    val consultationCurrency: String?
)
