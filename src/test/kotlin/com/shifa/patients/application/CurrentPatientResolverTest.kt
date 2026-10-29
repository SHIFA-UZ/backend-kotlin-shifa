package com.shifa.patients.application

import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import com.shifa.patients.domain.PatientProfile
import com.shifa.patients.infrastructure.PatientProfileRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.web.server.ResponseStatusException
import java.util.Optional

class CurrentPatientResolverTest {
    private val repo = mock(PatientProfileRepository::class.java)
    private val resolver = CurrentPatientResolver(repo)
    private val user = User(id = 7, email = "p@x.uz", phone = "+998901112233", passwordHash = "h", role = Role.PATIENT)
    private fun profile(id: Long) = PatientProfile(id = id, fullName = "A B")

    @Test
    fun `the profile linked by user id wins over phone and e-mail matches`() {
        `when`(repo.findNewestByUserId(7)).thenReturn(Optional.of(profile(1)))
        `when`(repo.findByPhone("+998901112233")).thenReturn(Optional.of(profile(2)))
        assertEquals(1L, resolver.resolve(user).id)
    }

    @Test
    fun `without a link the phone match comes before the e-mail match`() {
        `when`(repo.findNewestByUserId(7)).thenReturn(Optional.empty())
        `when`(repo.findByPhone("+998901112233")).thenReturn(Optional.of(profile(2)))
        `when`(repo.findByEmail("p@x.uz")).thenReturn(Optional.of(profile(3)))
        assertEquals(2L, resolver.resolve(user).id)
        `when`(repo.findByPhone("+998901112233")).thenReturn(Optional.empty())
        assertEquals(3L, resolver.resolve(user).id)
    }

    @Test
    fun `no profile anywhere is a 404, or null from find`() {
        `when`(repo.findNewestByUserId(7)).thenReturn(Optional.empty())
        `when`(repo.findByPhone("+998901112233")).thenReturn(Optional.empty())
        `when`(repo.findByEmail("p@x.uz")).thenReturn(Optional.empty())
        assertNull(resolver.find(user))
        val e = assertThrows(ResponseStatusException::class.java) { resolver.resolve(user) }
        assertEquals(404, e.statusCode.value())
    }
}
