package com.shifa.auth.application

import com.shifa.auth.domain.User
import com.shifa.auth.domain.UserSession
import com.shifa.auth.infrastructure.UserSessionRepository
import com.shifa.shared.security.UserSessionValidationCache
import java.time.OffsetDateTime
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/** A password change ends every other session of the account and keeps the caller's own. */
class UserManagementSessionRevokeTest {
    private val sessions = mock(UserSessionRepository::class.java)
    private val cache = mock(UserSessionValidationCache::class.java)
    private val service = UserManagementService(
        userRepository = mock(com.shifa.auth.infrastructure.UserRepository::class.java),
        adminProfileRepository = mock(com.shifa.admin.application.AdminProfiles::class.java),
        doctorProfileRepository = mock(com.shifa.doctors.application.DoctorProfiles::class.java),
        patientProfileRepository = mock(com.shifa.patients.application.PatientProfiles::class.java),
        userRoleRepository = mock(com.shifa.auth.infrastructure.UserRoleRepository::class.java),
        userSessionRepository = sessions,
        passwordEncoder = mock(org.springframework.security.crypto.password.PasswordEncoder::class.java),
        sessionValidationCache = cache,
    )

    @Test
    fun `revokes the other sessions and keeps the caller's`() {
        val user = User(id = 7, passwordHash = "x")
        val later = OffsetDateTime.now().plusDays(1)
        val own = UserSession(user = user, tokenJti = "own", expiresAt = later)
        val stolen = UserSession(user = user, tokenJti = "stolen", expiresAt = later)
        `when`(sessions.findActiveSessionsByUserId(ArgumentMatchers.eq(7L), ArgumentMatchers.any() ?: later))
            .thenReturn(listOf(own, stolen))

        service.revokeSessionsExcept(7, keepJti = "own")

        assertFalse(own.revoked)
        assertTrue(stolen.revoked)
        verify(cache).invalidateAll(listOf("stolen"))
    }
}
