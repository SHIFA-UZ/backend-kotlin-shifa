package com.shifa.security

import com.shifa.domain.Role
import com.shifa.repo.AdminProfileRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.UserRepository
import com.shifa.repo.UserRoleRepository
import com.shifa.security.PatientPrincipal
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

@Service
class UserDetailsServiceImpl(
    private val users: UserRepository,
    private val doctorProfiles: DoctorProfileRepository,
    private val adminProfiles: AdminProfileRepository,
    private val userRoles: UserRoleRepository
) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails {

        // 1️⃣ Load User (same logic you already had)
        val user = username.toLongOrNull()
            ?.let { users.findById(it).orElse(null) }
            ?: users.findByEmail(username).orElseGet {
                users.findByPhone(username)
                    .orElseThrow { UsernameNotFoundException(username) }
            }
            ?: throw UsernameNotFoundException(username)

        // 2️⃣ Load all roles from user_roles table (multi-role support)
        val userRoleEntities = userRoles.findByUserId(user.id)
        val roles = if (userRoleEntities.isNotEmpty()) {
            userRoleEntities.map { it.role }.toSet()
        } else {
            // Fallback: use users.role if user_roles is empty (backward compatibility)
            setOf(user.role)
        }

        // 3️⃣ Build authorities from all roles
        val authorities = roles.map { SimpleGrantedAuthority("ROLE_${it.name}") }.toList()

        // 4️⃣ Determine principal type based on primary role (users.role) for backward compatibility
        //    But include all roles in authorities so @PreAuthorize can check any role
        return when (user.role) {
            Role.ADMIN -> {
                val adminProfile = adminProfiles
                    .findByUserId(user.id)
                    .orElseThrow {
                        UsernameNotFoundException(
                            "Admin profile not found for user ${user.id}"
                        )
                    }
                AdminPrincipal.create(adminProfile, authorities)
            }
            Role.DOCTOR -> {
                val doctorProfile = doctorProfiles
                    .findByUserId(user.id)
                    .orElseThrow {
                        UsernameNotFoundException(
                            "Doctor profile not found for user ${user.id}"
                        )
                    }
                DoctorPrincipal(
                    profile = doctorProfile,
                    authorities = authorities
                )
            }
            Role.PATIENT -> {
                PatientPrincipal(
                    user = user,
                    authorities = authorities
                )
            }
        }
    }
}
