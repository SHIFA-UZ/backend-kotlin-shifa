package com.shifa.doctors.api

import com.shifa.clinics.application.ClinicAccessService
import com.shifa.doctors.api.dto.ColleagueDto
import com.shifa.doctors.api.dto.PracticeSessionDto
import com.shifa.shared.security.ClinicStaffPrincipal
import com.shifa.shared.security.DoctorPrincipal
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException


@RestController
@RequestMapping("/api/practice/me")
class PracticeMeController(
    private val clinicAccess: ClinicAccessService,
    private val doctors: com.shifa.doctors.application.DoctorProfiles,
) {

    @GetMapping
    fun me(@AuthenticationPrincipal principal: Any): PracticeSessionDto {
        clinicAccess.assertPracticeActor(principal)
        return when (principal) {
            is DoctorPrincipal -> {
                val cid = principal.profile.practiceClinic?.id
                val colleagues = if (cid != null) {
                    doctors.findAllByPracticeClinic_Id(cid)
                        .filter { it.id != principal.profile.id }
                        .map {
                            ColleagueDto(
                                doctorId = it.id!!,
                                displayName = "${it.firstName} ${it.lastName}".trim()
                            )
                        }
                } else {
                    emptyList()
                }
                PracticeSessionDto(
                    principalRole = "DOCTOR",
                    clinicIds = listOfNotNull(cid),
                    colleagues = colleagues
                )
            }
            is ClinicStaffPrincipal -> {
                val cids = principal.clinicIds().toList()
                val colleagues = cids.flatMap { doctors.findAllByPracticeClinic_Id(it) }
                    .distinctBy { it.id }
                    .map {
                        ColleagueDto(
                            doctorId = it.id!!,
                            displayName = "${it.firstName} ${it.lastName}".trim()
                        )
                    }
                PracticeSessionDto(
                    principalRole = "CLINIC_STAFF",
                    clinicIds = cids,
                    colleagues = colleagues
                )
            }
            else -> throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
    }
}
