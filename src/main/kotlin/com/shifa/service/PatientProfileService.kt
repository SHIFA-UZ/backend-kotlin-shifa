
// src/main/kotlin/com/shifa/service/PatientProfileService.kt
package com.shifa.service

import com.shifa.repo.PatientProfileRepository
import com.shifa.web.dto.PatientProfileDto
import com.shifa.service.PatientProfileMapper
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