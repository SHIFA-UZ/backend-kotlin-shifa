
// src/main/kotlin/com/shifa/web/dto/PatientProfileDto.kt
package com.shifa.web.dto

data class PatientProfileDto(
    val id: Long,
    val fullName: String,
    val phone: String?,
    val photoUrl: String?
)
