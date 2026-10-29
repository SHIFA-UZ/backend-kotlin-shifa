package com.shifa.shared.security

import com.shifa.clinics.domain.ClinicMembership
import com.shifa.auth.domain.User
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

class ClinicStaffPrincipal(
    val user: User,
    /** Active memberships only */
    val memberships: List<ClinicMembership>,
    private val authorities: Collection<GrantedAuthority>
) : UserDetails {

    fun clinicIds(): Set<Long> =
        memberships.filter { it.active }.map { it.clinic.id }.toSet()

    override fun getAuthorities(): Collection<GrantedAuthority> = authorities
    override fun getPassword(): String = user.passwordHash
    override fun getUsername(): String = user.id.toString()
    override fun isAccountNonExpired(): Boolean = true
    override fun isAccountNonLocked(): Boolean = true
    override fun isCredentialsNonExpired(): Boolean = true
    override fun isEnabled(): Boolean = user.enabled
}
