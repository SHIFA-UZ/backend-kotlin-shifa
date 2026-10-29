package com.shifa.doctors.api.dto

import com.shifa.doctors.api.PublicDoctorController.DoctorDto

data class DoctorListResponse(
    val totalCount: Long,
    val page: Int,
    val pageSize: Int,
    val doctors: List<DoctorDto>
)

data class ServicePriceDto(
    val amountMinor: Long,
    val currency: String,
    /** Null means the default price row for all locations (unless overridden per location). */
    val locationId: Long? = null
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
