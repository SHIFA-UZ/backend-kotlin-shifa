package com.shifa.web

import com.shifa.domain.PatientProphylaxisSetting
import com.shifa.repo.ClinicRepository
import com.shifa.repo.PatientProphylaxisSettingRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.service.ClinicAccessService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
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
    private val patients: PatientProfileRepository,
    private val settingsRepo: PatientProphylaxisSettingRepository,
) {

    data class SettingDto(
        val patientId: Long?,
        val clinicId: Long,
        val intervalMonths: Int,
        val enabled: Boolean,
        val lastSentAt: String?
    )

    data class UpsertSettingRequest(
        @field:NotNull val patientId: Long,
        @field:NotNull val clinicId: Long,
        @field:Min(1) @field:Max(60) val intervalMonths: Int,
        val enabled: Boolean
    )

    @GetMapping("/settings")
    @Transactional(readOnly = true)
    fun getSetting(
        @AuthenticationPrincipal principal: Any,
        @RequestParam patientId: Long,
        @RequestParam clinicId: Long
    ): SettingDto? {
        clinicAccess.assertPracticeActor(principal)
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        clinicAccess.assertPatientVisible(principal, patientId)
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
        clinicAccess.assertPrincipalMayAccessClinic(principal, body.clinicId)
        clinicAccess.assertPatientVisible(principal, body.patientId)
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
