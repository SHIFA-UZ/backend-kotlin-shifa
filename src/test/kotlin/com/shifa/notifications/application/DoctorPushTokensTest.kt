package com.shifa.notifications.application

import com.shifa.notifications.domain.DoctorPushToken
import com.shifa.notifications.infrastructure.DoctorPushTokenRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class DoctorPushTokensTest {

    private val repo = mock(DoctorPushTokenRepository::class.java)
    private val doctors = mock(com.shifa.doctors.application.DoctorProfiles::class.java)
    private val tokens = DoctorPushTokens(repo, doctors)

    @Test
    fun `a new device is added`() {
        tokens.register(5L, "tok-a", "ios", "jti-1")

        val saved = ArgumentCaptor.forClass(DoctorPushToken::class.java)
        verify(repo).save(saved.capture())
        assertEquals(5L, saved.value.doctorProfileId)
        assertEquals("tok-a", saved.value.token)
        assertEquals("ios", saved.value.platform)
        assertEquals("jti-1", saved.value.sessionJti)
    }

    @Test
    fun `registering again is idempotent and a device that changed account moves`() {
        val row = DoctorPushToken(id = 1L, doctorProfileId = 5L, token = "tok-a", platform = "android")
        `when`(repo.findByToken("tok-a")).thenReturn(row)

        tokens.register(9L, "tok-a", null, "jti-2")

        assertEquals(9L, row.doctorProfileId)
        assertEquals("jti-2", row.sessionJti)
        assertEquals("android", row.platform)
        verify(repo).save(row)
    }

    @Test
    fun `sign-out removes only the doctor's own token`() {
        val row = DoctorPushToken(id = 1L, doctorProfileId = 5L, token = "tok-a")
        `when`(repo.findByToken("tok-a")).thenReturn(row)

        tokens.unregister(9L, "tok-a")
        verify(repo, never()).delete(row)

        tokens.unregister(5L, "tok-a")
        verify(repo).delete(row)
    }

    @Test
    fun `the device's legacy token leaves the doctor it belonged to before, and goes at sign-out`() {
        val user = com.shifa.auth.domain.User(id = 1L, email = "a@x.uz", passwordHash = "h")
        val previous = com.shifa.doctors.domain.DoctorProfile(id = 5L, user = user, firstName = "A", lastName = "B")
            .also { it.fcmToken = "tok-a" }
        `when`(doctors.findByFcmToken("tok-a")).thenReturn(listOf(previous))

        tokens.register(9L, "tok-a", "android")
        assertEquals(null, previous.fcmToken)

        previous.fcmToken = "tok-a"
        tokens.unregister(5L, "tok-a")
        assertEquals(null, previous.fcmToken)
    }
}
