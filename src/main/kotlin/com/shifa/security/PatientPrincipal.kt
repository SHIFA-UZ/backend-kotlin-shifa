// src/main/kotlin/com/shifa/security/PatientPrincipal.kt
package com.shifa.security

import com.shifa.domain.User
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

class PatientPrincipal(
    val user: User,
    private val authorities: Collection<GrantedAuthority>
) : UserDetails {

    override fun getAuthorities() = authorities
    override fun getPassword() = user.passwordHash
    override fun getUsername() = user.id.toString()
    override fun isAccountNonExpired() = true
    override fun isAccountNonLocked() = !user.isLocked()
    override fun isCredentialsNonExpired() = true
    override fun isEnabled() = user.enabled
}
