package com.shifa.web

import com.shifa.domain.Role
import com.shifa.security.ClinicStaffPrincipal
import com.shifa.security.DoctorPrincipal
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Lightweight profile for authenticated practice actors (doctor or clinic staff).
 * Doctor app loads this instead of [/api/doctors/me] for users without a doctor profile.
 */
@RestController
class MeProfileController {

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

    @GetMapping("/api/me/profile")
    fun myProfile(@AuthenticationPrincipal principal: Any): MeProfileDto {
        return when (principal) {
            is DoctorPrincipal -> {
                val d = principal.profile
                val u = d.user
                MeProfileDto(
                    id = u.id,
                    role = Role.DOCTOR.name,
                    firstName = d.firstName.trim(),
                    lastName = d.lastName.trim(),
                    email = u.email,
                    phone = u.phone,
                    photoUrl = d.avatarUrl?.takeIf { it.isNotBlank() },
                    timeZone = d.timeZone?.takeIf { it.isNotBlank() },
                )
            }
            is ClinicStaffPrincipal -> {
                val u = principal.user
                MeProfileDto(
                    id = u.id,
                    role = Role.CLINIC_STAFF.name,
                    firstName = (u.staffFirstName ?: "").trim(),
                    lastName = (u.staffLastName ?: "").trim(),
                    email = u.email,
                    phone = u.phone,
                    photoUrl = u.staffPhotoUrl?.takeIf { it.isNotBlank() },
                    timeZone = u.staffTimeZone?.takeIf { it.isNotBlank() },
                )
            }
            else -> throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
    }
}
