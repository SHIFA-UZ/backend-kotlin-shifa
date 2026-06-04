package com.shifa.service

import com.shifa.domain.Clinic
import com.shifa.domain.ClinicMembership
import com.shifa.domain.DoctorProfile
import com.shifa.domain.Role
import com.shifa.domain.User
import com.shifa.repo.ClinicMembershipRepository
import com.shifa.repo.ClinicRepository
import com.shifa.repo.DoctorProfileRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.Optional

class AdminClinicServiceTest {

    private val clinics = mock(ClinicRepository::class.java)
    private val doctors = mock(DoctorProfileRepository::class.java)
    private val memberships = mock(ClinicMembershipRepository::class.java)

    private val service = AdminClinicService(clinics, doctors, memberships)

    private fun doctorUser(id: Long) =
        User(id = id, passwordHash = "x", role = Role.DOCTOR, enabled = true)

    private fun doctorProfile(id: Long, user: User, clinic: Clinic) =
        DoctorProfile(id = id, user = user, firstName = "Doc", lastName = "$id", practiceClinic = clinic)

    @Test
    fun `promote to owner demotes existing owner to doctor`() {
        val clinic = Clinic(id = 10L, name = "Alpha")
        val ownerUser = doctorUser(1L)
        val newOwnerUser = doctorUser(2L)
        val ownerDoc = doctorProfile(101L, ownerUser, clinic)
        val newOwnerDoc = doctorProfile(102L, newOwnerUser, clinic)
        val ownerMembership = ClinicMembership(
            clinic = clinic,
            user = ownerUser,
            membershipRole = ClinicMembership.MembershipRole.OWNER,
            doctorProfile = ownerDoc,
            active = true,
        )
        val newOwnerMembership = ClinicMembership(
            clinic = clinic,
            user = newOwnerUser,
            membershipRole = ClinicMembership.MembershipRole.DOCTOR,
            doctorProfile = newOwnerDoc,
            active = true,
        )

        `when`(clinics.existsById(10L)).thenReturn(true)
        `when`(doctors.findById(102L)).thenReturn(Optional.of(newOwnerDoc))
        `when`(memberships.findByClinic_IdAndUser_Id(10L, 2L)).thenReturn(newOwnerMembership)
        `when`(memberships.findByClinicIdAndActiveTrue(10L)).thenReturn(listOf(ownerMembership, newOwnerMembership))
        `when`(clinics.findById(10L)).thenReturn(Optional.of(clinic))
        `when`(doctors.findAllByPracticeClinic_Id(10L)).thenReturn(listOf(ownerDoc, newOwnerDoc))
        `when`(memberships.findByClinic_IdAndUser_Id(10L, 1L)).thenReturn(ownerMembership)
        `when`(memberships.findByClinic_IdAndUser_Id(10L, 2L)).thenReturn(newOwnerMembership)
        `when`(memberships.save(any(ClinicMembership::class.java))).thenAnswer { it.arguments[0] }

        service.updateMemberRole(10L, 102L, ClinicMembership.MembershipRole.OWNER)

        assertEquals(ClinicMembership.MembershipRole.DOCTOR, ownerMembership.membershipRole)
        assertEquals(ClinicMembership.MembershipRole.OWNER, newOwnerMembership.membershipRole)
        verify(memberships, times(2)).save(any(ClinicMembership::class.java))
    }

    @Test
    fun `invalid role is rejected`() {
        val clinic = Clinic(id = 10L, name = "Alpha")
        val user = doctorUser(1L)
        val doc = doctorProfile(101L, user, clinic)
        val membership = ClinicMembership(
            clinic = clinic,
            user = user,
            membershipRole = ClinicMembership.MembershipRole.DOCTOR,
            doctorProfile = doc,
            active = true,
        )

        `when`(clinics.existsById(10L)).thenReturn(true)
        `when`(doctors.findById(101L)).thenReturn(Optional.of(doc))
        `when`(memberships.findByClinic_IdAndUser_Id(10L, 1L)).thenReturn(membership)

        val ex = assertThrows(ResponseStatusException::class.java) {
            service.updateMemberRole(10L, 101L, ClinicMembership.MembershipRole.RECEPTIONIST)
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        assertEquals("Invalid membership role", ex.reason)
    }

    @Test
    fun `last owner demotion is blocked`() {
        val clinic = Clinic(id = 10L, name = "Alpha")
        val user = doctorUser(1L)
        val doc = doctorProfile(101L, user, clinic)
        val ownerMembership = ClinicMembership(
            clinic = clinic,
            user = user,
            membershipRole = ClinicMembership.MembershipRole.OWNER,
            doctorProfile = doc,
            active = true,
        )

        `when`(clinics.existsById(10L)).thenReturn(true)
        `when`(doctors.findById(101L)).thenReturn(Optional.of(doc))
        `when`(memberships.findByClinic_IdAndUser_Id(10L, 1L)).thenReturn(ownerMembership)
        `when`(memberships.findByClinicIdAndActiveTrue(10L)).thenReturn(listOf(ownerMembership))

        val ex = assertThrows(ResponseStatusException::class.java) {
            service.updateMemberRole(10L, 101L, ClinicMembership.MembershipRole.DOCTOR)
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        assertEquals("Clinic must have an owner", ex.reason)
    }

    @Test
    fun `owner can be demoted when another owner exists`() {
        val clinic = Clinic(id = 10L, name = "Alpha")
        val ownerUser = doctorUser(1L)
        val otherOwnerUser = doctorUser(2L)
        val ownerDoc = doctorProfile(101L, ownerUser, clinic)
        val otherOwnerDoc = doctorProfile(102L, otherOwnerUser, clinic)
        val ownerMembership = ClinicMembership(
            clinic = clinic,
            user = ownerUser,
            membershipRole = ClinicMembership.MembershipRole.OWNER,
            doctorProfile = ownerDoc,
            active = true,
        )
        val otherOwnerMembership = ClinicMembership(
            clinic = clinic,
            user = otherOwnerUser,
            membershipRole = ClinicMembership.MembershipRole.OWNER,
            doctorProfile = otherOwnerDoc,
            active = true,
        )

        `when`(clinics.existsById(10L)).thenReturn(true)
        `when`(doctors.findById(101L)).thenReturn(Optional.of(ownerDoc))
        `when`(memberships.findByClinic_IdAndUser_Id(10L, 1L)).thenReturn(ownerMembership)
        `when`(memberships.findByClinicIdAndActiveTrue(10L)).thenReturn(listOf(ownerMembership, otherOwnerMembership))
        `when`(clinics.findById(10L)).thenReturn(Optional.of(clinic))
        `when`(doctors.findAllByPracticeClinic_Id(10L)).thenReturn(listOf(ownerDoc, otherOwnerDoc))
        `when`(memberships.findByClinic_IdAndUser_Id(10L, 1L)).thenReturn(ownerMembership)
        `when`(memberships.findByClinic_IdAndUser_Id(10L, 2L)).thenReturn(otherOwnerMembership)
        `when`(memberships.save(any(ClinicMembership::class.java))).thenAnswer { it.arguments[0] }

        service.updateMemberRole(10L, 101L, ClinicMembership.MembershipRole.CLINIC_ADMIN)

        assertEquals(ClinicMembership.MembershipRole.CLINIC_ADMIN, ownerMembership.membershipRole)
    }
}
