package com.shifa.doctors.api.dto

data class LocationDto(
    val id: Long?,
    val label: String,
    val clinic: String? = null,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationCountry: String? = null,
    val locationRegion: String? = null,
    val locationDistrict: String? = null,
    val locationCity: String? = null,
    val locationPostalCode: String? = null,
    val locationStreetAddress: String? = null,
    val isPrimary: Boolean = false
)
