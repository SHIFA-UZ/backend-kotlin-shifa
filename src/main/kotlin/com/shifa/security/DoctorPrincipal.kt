// src/main/kotlin/com/shifa/security/DoctorPrincipal.kt
package com.shifa.security

import com.shifa.domain.DoctorProfile
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

class DoctorPrincipal(
    val profile: DoctorProfile,
    private val authorities: Collection<GrantedAuthority>
) : UserDetails {

    override fun getAuthorities() = authorities
    override fun getPassword() = profile.user.passwordHash
    override fun getUsername() = profile.user.id.toString()
    override fun isAccountNonExpired() = true
    override fun isAccountNonLocked() = true
    override fun isCredentialsNonExpired() = true
    override fun isEnabled() = profile.user.enabled
}
