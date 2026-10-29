package com.shifa.clinics.application

import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import com.shifa.clinics.domain.Clinic
import com.shifa.clinics.domain.ClinicMembership
import com.shifa.clinics.infrastructure.ClinicMembershipRepository
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.shared.security.DoctorPrincipal
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.web.server.ResponseStatusException

class ClinicFinanceAccessServiceTest {

    private val memberships = mock(ClinicMembershipRepository::class.java)
    private val clinicAccess = mock(ClinicAccessService::class.java)
    private val patients = mock(com.shifa.patients.application.PatientProfiles::class.java)

    private val service = ClinicFinanceAccessService(memberships, clinicAccess, patients)

    @Test
    fun `practice clinic owner may manage finance settings`() {
        val clinic = Clinic(id = 10L, name = "SEBAMED")
        val user = User(id = 1L, passwordHash = "x", role = Role.DOCTOR, enabled = true)
        val doctor = DoctorProfile(id = 100L, user = user, firstName = "Nigora", lastName = "O", practiceClinic = clinic)
        val membership = ClinicMembership(
            clinic = clinic,
            user = user,
            membershipRole = ClinicMembership.MembershipRole.OWNER,
            doctorProfile = doctor,
            active = true,
        )
        `when`(memberships.findByUserIdAndClinicIdAndActiveTrue(user.id, clinic.id)).thenReturn(membership)

        assertDoesNotThrow {
            service.assertCanManageFinanceSettings(
                DoctorPrincipal(doctor, listOf(SimpleGrantedAuthority("ROLE_DOCTOR"))),
                clinic.id,
            )
        }
    }

    @Test
    fun `practice clinic doctor without owner membership cannot manage finance settings`() {
        val clinic = Clinic(id = 10L, name = "SEBAMED")
        val user = User(id = 2L, passwordHash = "x", role = Role.DOCTOR, enabled = true)
        val doctor = DoctorProfile(id = 101L, user = user, firstName = "Doc", lastName = "Two", practiceClinic = clinic)
        val membership = ClinicMembership(
            clinic = clinic,
            user = user,
            membershipRole = ClinicMembership.MembershipRole.DOCTOR,
            doctorProfile = doctor,
            active = true,
        )
        `when`(memberships.findByUserIdAndClinicIdAndActiveTrue(user.id, clinic.id)).thenReturn(membership)

        assertThrows(ResponseStatusException::class.java) {
            service.assertCanManageFinanceSettings(
                DoctorPrincipal(doctor, listOf(SimpleGrantedAuthority("ROLE_DOCTOR"))),
                clinic.id,
            )
        }
    }
}
