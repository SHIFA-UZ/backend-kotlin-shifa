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
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.transaction.support.TransactionSynchronizationManager
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
        verify(cache).markRevoked(listOf("stolen"))
    }

    /** The logout race: a request that read the row just before the revoke must not cache the session as valid. */
    @Test
    fun `a revoked session cannot be put back into the cache as valid`() {
        val real = UserSessionValidationCache()
        val later = OffsetDateTime.now().plusDays(1)
        real.putValid("jti", later)
        assertTrue(real.isCurrentlyValid(real.get("jti")!!))

        real.markRevoked(listOf("jti"))
        real.putValid("jti", later) // the late write of the request that read before the revoke

        assertFalse(real.isCurrentlyValid(real.get("jti")!!))
    }

    @Test
    fun `inside a transaction the cache hears of a revoke only after the commit`() {
        val user = User(id = 7, passwordHash = "x")
        val session = UserSession(user = user, tokenJti = "own", expiresAt = OffsetDateTime.now().plusDays(1))
        `when`(sessions.findByTokenJti("own")).thenReturn(java.util.Optional.of(session))
        TransactionSynchronizationManager.initSynchronization()
        try {
            service.revokeSession(7, "own")
            assertTrue(session.revoked)
            verifyNoInteractions(cache)
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
            verify(cache).markRevoked(listOf("own"))
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }
}
