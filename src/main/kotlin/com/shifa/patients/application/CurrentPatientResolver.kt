package com.shifa.patients.application

import com.shifa.auth.domain.User
import com.shifa.patients.domain.PatientProfile
import com.shifa.patients.infrastructure.PatientProfileRepository
import com.shifa.shared.security.PatientPrincipal
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * The one way to find the signed-in patient's profile (roadmap Phase 5, `shared` step).
 *
 * Order: the profile linked by `user_id` (authoritative, also right for a user who is doctor and patient),
 * then the newest profile with the user's exact phone, then the newest with the exact e-mail, for profiles
 * created before the link existed. Replaces nine private copies that disagreed on the order (three put the link
 * first, one last, five never used it). `docs/sql/patient-resolution-check.sql` counts the production users for
 * whom the orders disagree: on 2026-10-29 none of 70 changed and five, unresolvable before, gained a profile.
 */
@Component
class CurrentPatientResolver(private val patientProfiles: PatientProfileRepository) {

    fun resolve(principal: PatientPrincipal): PatientProfile = resolve(principal.user)

    fun resolve(user: User): PatientProfile =
        find(user) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient profile not found for user ${user.id}")

    fun find(user: User): PatientProfile? =
        patientProfiles.findNewestByUserId(user.id).orElse(null)
            ?: user.phone?.trim()?.takeIf { it.isNotEmpty() }?.let { patientProfiles.findByPhone(it).orElse(null) }
            ?: user.email?.trim()?.takeIf { it.isNotEmpty() }?.let { patientProfiles.findByEmail(it).orElse(null) }
}
