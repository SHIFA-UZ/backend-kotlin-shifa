
// src/main/kotlin/com/shifa/patients/application/PatientProfileService.kt
package com.shifa.patients.application

import com.shifa.patients.infrastructure.PatientProfileRepository
import com.shifa.patients.api.PatientProfileDto
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PatientProfileService(
    private val repo: PatientProfileRepository,
    private val mapper: PatientProfileMapper
) {
    @Transactional(readOnly = true)
    fun findAll(): List<PatientProfileDto> =
        repo.findAll().map { mapper.toDto(it) }

    @Transactional(readOnly = true)
    fun findById(id: Long): PatientProfileDto? =
        repo.findById(id).orElse(null)?.let { mapper.toDto(it) }
}