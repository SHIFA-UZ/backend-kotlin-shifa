package com.shifa.service

import com.shifa.domain.Role
import com.shifa.domain.SubscriptionFeature
import com.shifa.domain.SubscriptionTier
import com.shifa.domain.User
import com.shifa.repo.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

/**
 * Single place that resolves and updates each user's admin-managed subscription tier and
 * enforces feature gates server-side. Frontend mirrors the gates but we treat the backend as the
 * authoritative source.
 */
@Service
class SubscriptionTierService(
    private val userRepository: UserRepository
) {

    /**
     * Features that belong to the patient surface. Everything else is
     * considered doctor-side. Used so a user's `features` list doesn't leak
     * features that don't apply to their role.
     */
    private val patientOnlyFeatures: Set<SubscriptionFeature> = setOf(
        SubscriptionFeature.PATIENT_SHIFA_AI
    )

    /** Features that apply to a given role. Admins see all features. */
    private fun featuresForRole(role: Role): Set<SubscriptionFeature> = when (role) {
        Role.ADMIN -> SubscriptionFeature.entries.toSet()
        Role.PATIENT -> patientOnlyFeatures
        Role.DOCTOR -> SubscriptionFeature.entries.toSet() - patientOnlyFeatures
    }

    /** Resolve a user's effective tier. Admins are always treated as PREMIUM. */
    fun tierOf(user: User): SubscriptionTier =
        if (user.role == Role.ADMIN) SubscriptionTier.PREMIUM else user.subscriptionTier

    /** Returns true when the user's role + tier permits the requested feature. */
    fun canUse(user: User, feature: SubscriptionFeature): Boolean {
        if (user.role == Role.ADMIN) return true
        if (feature !in featuresForRole(user.role)) return false
        return tierOf(user).allows(feature)
    }

    /**
     * Throws a 403 with a stable error code when the user's plan does not include the feature.
     * Use from controllers/services that gate functionality.
     */
    fun requireFeature(user: User, feature: SubscriptionFeature) {
        if (!canUse(user, feature)) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Feature ${feature.name} requires a higher subscription tier (${feature.minTier.name})."
            )
        }
    }

    /**
     * Update a user's tier from the admin panel. Validates role-tier compatibility:
     *  - PATIENT cannot be BASIC (only PRO or PREMIUM)
     *  - ADMIN tier cannot be downgraded (admins are always PREMIUM)
     */
    @Transactional
    fun setTier(userId: Long, tier: SubscriptionTier): User {
        val user = userRepository.findById(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: $userId") }

        when (user.role) {
            Role.ADMIN -> throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Admin users always have PREMIUM access; their tier is not configurable."
            )
            Role.PATIENT -> if (tier == SubscriptionTier.BASIC) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Patients can only be assigned PRO or PREMIUM."
                )
            }
            Role.DOCTOR -> { /* all three tiers are valid */ }
        }

        user.subscriptionTier = tier
        return userRepository.save(user)
    }

    /**
     * Feature codes available for a given user, scoped to features that apply to their role.
     * Used by frontends to render gates without leaking cross-role feature names.
     */
    fun availableFeatures(user: User): List<SubscriptionFeature> =
        featuresForRole(user.role).filter { canUse(user, it) }
}
