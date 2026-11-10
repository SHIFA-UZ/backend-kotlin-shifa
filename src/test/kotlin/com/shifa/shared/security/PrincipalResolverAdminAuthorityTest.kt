package com.shifa.shared.security

import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import com.shifa.auth.domain.UserRole
import com.shifa.auth.infrastructure.UserRepository
import com.shifa.auth.infrastructure.UserRoleRepository
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** An account that is both patient and admin: a patient token must not carry the admin authority. */
class PrincipalResolverAdminAuthorityTest {
    @Test
    fun `a patient token of an admin account has no admin authority`() {
        val user = User(id = 5, passwordHash = "x", role = Role.ADMIN)
        val users = mock(UserRepository::class.java)
        val userRoles = mock(UserRoleRepository::class.java)
        `when`(users.findById(5L)).thenReturn(Optional.of(user))
        `when`(userRoles.findByUserId(5L)).thenReturn(listOf(UserRole(user = user, role = Role.ADMIN), UserRole(user = user, role = Role.PATIENT)))
        val resolver = PrincipalResolverService(
            users,
            userRoles,
            mock(com.shifa.doctors.infrastructure.DoctorProfileRepository::class.java),
            mock(com.shifa.admin.infrastructure.AdminProfileRepository::class.java),
            mock(com.shifa.clinics.infrastructure.ClinicMembershipRepository::class.java),
        )

        val principal = resolver.getPrincipalForToken(5, "PATIENT")

        assertEquals(listOf("ROLE_PATIENT"), principal.authorities.map { it.authority })
    }
}
