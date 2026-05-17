package com.shifa.security

import com.shifa.domain.Role
import com.shifa.domain.User
import com.shifa.repo.AdminProfileRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.UserRepository
import com.shifa.repo.UserRoleRepository
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

/**
 * Resolves the correct principal type based on JWT token context (userId + optional role claim).
 * When a user has multiple roles (e.g. DOCTOR and PATIENT), the token's "role" claim determines
 * which principal to build so that doctor app gets DoctorPrincipal and patient app gets PatientPrincipal.
 */
@Service
class PrincipalResolverService(
    private val users: UserRepository,
    private val userRoles: UserRoleRepository,
    private val doctorProfiles: DoctorProfileRepository,
    private val adminProfiles: AdminProfileRepository,
    private val clinicMemberships: com.shifa.repo.ClinicMembershipRepository
) {

    /**
     * Build principal for request: use tokenRole when present (and user has that role), otherwise primary role (users.role).
     */
    fun getPrincipalForToken(userId: Long, tokenRole: String?): UserDetails {
        val user = users.findById(userId).orElseThrow { UsernameNotFoundException("User not found: $userId") }
        val roles = userRoles.findByUserId(user.id)
            .takeIf { it.isNotEmpty() }
            ?.map { it.role }?.toSet()
            ?: setOf(user.role)
        val authorities = roles.map { SimpleGrantedAuthority("ROLE_${it.name}") }.toList()

        val effectiveRole = when {
            tokenRole != null -> {
                val role = runCatching { Role.valueOf(tokenRole) }.getOrNull()
                    ?: return resolveByPrimaryRole(user, authorities)
                if (!roles.contains(role)) {
                    // Token claims a role user doesn't have (e.g. tampered) -> reject by using primary only or throw
                    return resolveByPrimaryRole(user, authorities)
                }
                role
            }
            else -> user.role
        }

        return when (effectiveRole) {
            Role.ADMIN -> {
                val adminProfile = adminProfiles.findByUserId(user.id).orElseThrow {
                    UsernameNotFoundException("Admin profile not found for user ${user.id}")
                }
                AdminPrincipal.create(adminProfile, authorities)
            }
            Role.DOCTOR -> {
                val doctorProfile = doctorProfiles.findByUserId(user.id).orElseThrow {
                    UsernameNotFoundException("Doctor profile not found for user ${user.id}")
                }
                DoctorPrincipal(profile = doctorProfile, authorities = authorities)
            }
            Role.CLINIC_STAFF -> {
                val memberships = clinicMemberships.findByUserIdAndActiveTrue(user.id)
                if (memberships.isEmpty()) {
                    throw UsernameNotFoundException("Clinic membership not found for staff user ${user.id}")
                }
                ClinicStaffPrincipal(user = user, memberships = memberships, authorities = authorities)
            }
            Role.PATIENT -> PatientPrincipal(user = user, authorities = authorities)
        }
    }

    private fun resolveByPrimaryRole(user: User, authorities: List<GrantedAuthority>): UserDetails {
        return when (user.role) {
            Role.ADMIN -> {
                val adminProfile = adminProfiles.findByUserId(user.id).orElseThrow {
                    UsernameNotFoundException("Admin profile not found for user ${user.id}")
                }
                AdminPrincipal.create(adminProfile, authorities)
            }
            Role.DOCTOR -> {
                val doctorProfile = doctorProfiles.findByUserId(user.id).orElseThrow {
                    UsernameNotFoundException("Doctor profile not found for user ${user.id}")
                }
                DoctorPrincipal(profile = doctorProfile, authorities = authorities)
            }
            Role.CLINIC_STAFF -> {
                val memberships = clinicMemberships.findByUserIdAndActiveTrue(user.id)
                if (memberships.isEmpty()) {
                    throw UsernameNotFoundException("Clinic membership not found for staff user ${user.id}")
                }
                ClinicStaffPrincipal(user = user, memberships = memberships, authorities = authorities)
            }
            Role.PATIENT -> PatientPrincipal(user = user, authorities = authorities)
        }
    }
}
