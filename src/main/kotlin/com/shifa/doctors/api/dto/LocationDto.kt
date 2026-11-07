package com.shifa.doctors.api.dto

import com.shifa.doctors.domain.DoctorLocation

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

fun DoctorLocation.toLocationDto(): LocationDto = LocationDto(
    id = id,
    label = label,
    clinic = clinic,
    address = address,
    latitude = latitude,
    longitude = longitude,
    locationCountry = locationCountry,
    locationRegion = locationRegion,
    locationDistrict = locationDistrict,
    locationCity = locationCity,
    locationPostalCode = locationPostalCode,
    locationStreetAddress = locationStreetAddress,
    isPrimary = isPrimary,
)
