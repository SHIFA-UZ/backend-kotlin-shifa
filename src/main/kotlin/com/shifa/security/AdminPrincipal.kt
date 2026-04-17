package com.shifa.security

import com.shifa.domain.AdminProfile
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

class AdminPrincipal(
    val adminProfile: AdminProfile,
    private val authorities: Collection<GrantedAuthority>
) : UserDetails {

    override fun getAuthorities() = authorities
    override fun getPassword() = adminProfile.user.passwordHash
    override fun getUsername() = adminProfile.user.id.toString()
    override fun isAccountNonExpired() = true
    override fun isAccountNonLocked() = !adminProfile.user.isLocked()
    override fun isCredentialsNonExpired() = true
    override fun isEnabled() = adminProfile.user.enabled
    
    fun isSuperAdmin(): Boolean = adminProfile.adminLevel.name == "SUPER_ADMIN"
    fun isReadOnly(): Boolean = adminProfile.adminLevel.name == "READ_ONLY"
    
    companion object {
        fun create(adminProfile: AdminProfile, allRolesAuthorities: Collection<GrantedAuthority>): AdminPrincipal {
            val authorities = mutableListOf<GrantedAuthority>().apply {
                // Add all roles from user_roles (multi-role support)
                addAll(allRolesAuthorities)
                // Add admin-level specific authorities
                when (adminProfile.adminLevel.name) {
                    "SUPER_ADMIN" -> add(SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
                    "READ_ONLY" -> add(SimpleGrantedAuthority("ROLE_READ_ONLY"))
                }
            }
            return AdminPrincipal(adminProfile, authorities)
        }
    }
}
