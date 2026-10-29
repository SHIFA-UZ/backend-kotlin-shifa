
// src/main/kotlin/com/shifa/patients/api/PatientProfileDto.kt
package com.shifa.patients.api

data class PatientProfileDto(
    val id: Long,
    val fullName: String,
    val phone: String?,
    val photoUrl: String?
)
