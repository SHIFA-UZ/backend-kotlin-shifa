package com.shifa.service

import com.shifa.domain.PatientProfile
import com.shifa.domain.Role
import com.shifa.domain.User
import com.shifa.repo.PatientProfileRepository
import com.shifa.repo.UserRepository
import com.shifa.repo.UserRoleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.server.ResponseStatusException
import java.util.Optional

class PatientAccountServiceTest {

    private val userRepository = mock(UserRepository::class.java)
    private val patientProfileRepository = mock(PatientProfileRepository::class.java)
    private val passwordEncoder = mock(PasswordEncoder::class.java)
    private val userRoleRepository = mock(UserRoleRepository::class.java)

    private val service = PatientAccountService(
        userRepository,
        patientProfileRepository,
        passwordEncoder,
        userRoleRepository,
    )

    private fun patient(id: Long = 241L, email: String? = null, phone: String? = null): PatientProfile {
        val p = PatientProfile(id = id, fullName = "Jane Doe")
        p.email = email
        p.phone = phone
        return p
    }

    @Test
    fun `createPatientAccount rejects when patient already linked to user`() {
        val linkedUser = User(id = 9L, passwordHash = "x", role = Role.PATIENT, enabled = true)
        val p = patient()
        p.user = linkedUser
        `when`(patientProfileRepository.findById(241L)).thenReturn(Optional.of(p))

        val ex = assertThrows<ResponseStatusException> {
            service.createPatientAccount(241L)
        }
        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any(User::class.java))
    }

    @Test
    fun `createPatientAccount rejects duplicate email`() {
        val p = patient(email = "exists@example.com")
        `when`(patientProfileRepository.findById(241L)).thenReturn(Optional.of(p))
        `when`(userRepository.findByEmailIgnoreCase("exists@example.com"))
            .thenReturn(Optional.of(User(id = 1L, passwordHash = "x", role = Role.PATIENT, enabled = true)))

        val ex = assertThrows<ResponseStatusException> {
            service.createPatientAccount(241L)
        }
        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        assertEquals("Email already registered", ex.reason)
    }

    @Test
    fun `createPatientAccount rejects duplicate phone`() {
        val p = patient(phone = "+998901234567")
        p.phoneNormalized = "+998901234567"
        `when`(patientProfileRepository.findById(241L)).thenReturn(Optional.of(p))
        `when`(userRepository.findByPhone("+998901234567"))
            .thenReturn(Optional.of(User(id = 1L, passwordHash = "x", role = Role.PATIENT, enabled = true)))

        val ex = assertThrows<ResponseStatusException> {
            service.createPatientAccount(241L)
        }
        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        assertEquals("Phone number already registered", ex.reason)
    }
}
