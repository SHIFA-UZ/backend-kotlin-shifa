
// src/main/kotlin/com/shifa/patients/application/PatientProfileService.kt
package com.shifa.patients.application

import com.shifa.patients.infrastructure.PatientProfileRepository
import com.shifa.patients.api.PatientProfileDto
import org.springframework.stereotype.Service

@Service
class PatientProfileService(
    private val repo: PatientProfileRepository,
    private val mapper: PatientProfileMapper
) {
    fun findAll(): List<PatientProfileDto> =
        repo.findAll().map { mapper.toDto(it) }

    fun findById(id: Long): PatientProfileDto? =
        repo.findById(id).orElse(null)?.let { mapper.toDto(it) }
}