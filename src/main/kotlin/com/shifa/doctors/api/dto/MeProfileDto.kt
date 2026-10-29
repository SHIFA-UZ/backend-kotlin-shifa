package com.shifa.doctors.api.dto

data class MeProfileDto(
    val id: Long,
    val role: String,
    val firstName: String,
    val lastName: String,
    val email: String?,
    val phone: String?,
    val photoUrl: String?,
    val timeZone: String?,
)
