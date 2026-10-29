package com.shifa.patients.api

import com.shifa.clinics.application.ClinicAccessService
import com.shifa.clinics.infrastructure.ClinicRepository
import com.shifa.patients.api.dto.SettingDto
import com.shifa.patients.api.dto.UpsertSettingRequest
import com.shifa.patients.domain.PatientProphylaxisSetting
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException


@RestController
@RequestMapping("/api/prophylaxis")
class ProphylaxisController(
    private val clinicAccess: ClinicAccessService,
    private val clinics: ClinicRepository,
    private val patients: com.shifa.patients.application.PatientProfiles,
    private val settingsRepo: com.shifa.patients.application.PatientProphylaxisSettings,
) {

    @GetMapping("/settings")
    @Transactional(readOnly = true)
    fun getSetting(
        @AuthenticationPrincipal principal: Any,
        @RequestParam patientId: Long,
        @RequestParam clinicId: Long
    ): SettingDto? {
        clinicAccess.assertPracticeActor(principal)
        clinicAccess.assertPatientLinkedToClinic(principal, patientId, clinicId)
        val s = settingsRepo.findByPatient_IdAndClinic_Id(patientId, clinicId) ?: return null
        return SettingDto(
            patientId = s.patient.id,
            clinicId = s.clinic.id,
            intervalMonths = s.intervalMonths,
            enabled = s.enabled,
            lastSentAt = s.lastSentAt?.toString()
        )
    }

    @PutMapping("/settings")
    @Transactional
    fun upsertSetting(
        @AuthenticationPrincipal principal: Any,
        @RequestBody @Valid body: UpsertSettingRequest
    ): SettingDto {
        clinicAccess.assertPracticeActor(principal)
        clinicAccess.assertPatientLinkedToClinic(principal, body.patientId, body.clinicId)
        val clinic = clinics.findById(body.clinicId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Clinic not found")
        }
        val patient = patients.findById(body.patientId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
        }
        val existing = settingsRepo.findByPatient_IdAndClinic_Id(body.patientId, body.clinicId)
        val saved = if (existing != null) {
            existing.intervalMonths = body.intervalMonths
            existing.enabled = body.enabled
            settingsRepo.save(existing)
        } else {
            settingsRepo.save(
                PatientProphylaxisSetting(
                    patient = patient,
                    clinic = clinic,
                    intervalMonths = body.intervalMonths,
                    enabled = body.enabled
                )
            )
        }
        return SettingDto(
            patientId = saved.patient.id,
            clinicId = saved.clinic.id,
            intervalMonths = saved.intervalMonths,
            enabled = saved.enabled,
            lastSentAt = saved.lastSentAt?.toString()
        )
    }
}
