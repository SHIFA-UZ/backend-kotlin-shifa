package com.shifa.notifications.application

import com.shifa.notifications.infrastructure.FcmService
import com.shifa.patients.domain.PatientProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify


class FcmTokenCleanupServiceTest {

    private val patients = mock(com.shifa.patients.application.PatientProfiles::class.java)
    private val doctors = mock(com.shifa.doctors.application.DoctorProfiles::class.java)
    private val service = FcmTokenCleanupService(patients, doctors)

    @Test
    fun `clears matching patient token`() {
        val token = "eI1dNy12Qx-stale-token"
        val patient = PatientProfile(id = 59L, fullName = "Test", fcmToken = token)
        `when`(patients.findByFcmToken(token)).thenReturn(listOf(patient))
        `when`(doctors.findByFcmToken(token)).thenReturn(emptyList())

        service.clearToken(token)

        assertNull(patient.fcmToken)
        verify(patients).saveAll(listOf(patient))
        verify(doctors, never()).saveAll(org.mockito.ArgumentMatchers.anyList())
    }

    @Test
    fun `ignores blank token`() {
        service.clearToken("  ")
        verify(patients, never()).findByFcmToken(org.mockito.ArgumentMatchers.anyString())
        verify(doctors, never()).findByFcmToken(org.mockito.ArgumentMatchers.anyString())
    }
}

class FcmPermanentTokenFailureTest {

    @Test
    fun `sender id mismatch message is permanent`() {
        assertTrue(FcmService.isPermanentTokenFailure(RuntimeException("SenderId mismatch")))
    }

    @Test
    fun `transient failures are not permanent`() {
        assertFalse(FcmService.isPermanentTokenFailure(RuntimeException("UNAVAILABLE")))
        assertFalse(FcmService.isPermanentTokenFailure(RuntimeException("quota exceeded")))
    }

    @Test
    fun `empty message is not permanent`() {
        assertFalse(FcmService.isPermanentTokenFailure(RuntimeException()))
        assertEquals(false, FcmService.isPermanentTokenFailure(IllegalStateException("network timeout")))
    }
}
