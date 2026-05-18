package com.shifa.service

import com.shifa.domain.Clinic
import com.shifa.domain.ClinicMembership
import com.shifa.domain.DoctorProfile
import com.shifa.domain.PatientProfile
import com.shifa.domain.Role
import com.shifa.domain.User
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.ClinicMembershipRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.security.ClinicStaffPrincipal
import com.shifa.security.DoctorPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.web.server.ResponseStatusException
import java.util.Optional

class ClinicAccessServiceTest {

    private val doctors = mock(DoctorProfileRepository::class.java)
    private val appointments = mock(AppointmentRepository::class.java)
    private val patients = mock(PatientProfileRepository::class.java)
    private val memberships = mock(ClinicMembershipRepository::class.java)

    private val service = ClinicAccessService(doctors, appointments, patients, memberships)

    private fun doctorUser(id: Long) =
        User(id = id, passwordHash = "x", role = Role.DOCTOR, enabled = true)

    private fun doctorPrincipal(profile: DoctorProfile) =
        DoctorPrincipal(profile, listOf(SimpleGrantedAuthority("ROLE_DOCTOR")))

    @Test
    fun `doctor may view colleague calendar in same clinic`() {
        val clinic = Clinic(id = 10L, name = "Alpha")
        val userA = doctorUser(1L)
        val userB = doctorUser(2L)
        val docA = DoctorProfile(id = 101L, user = userA, firstName = "A", lastName = "One", practiceClinic = clinic)
        val docB = DoctorProfile(id = 102L, user = userB, firstName = "B", lastName = "Two", practiceClinic = clinic)

        `when`(doctors.findById(102L)).thenReturn(Optional.of(docB))

        assertTrue(service.canViewDoctorCalendar(doctorPrincipal(docA), 102L))
    }

    @Test
    fun `doctor may not view calendar of doctor in another clinic`() {
        val clinicA = Clinic(id = 10L, name = "Alpha")
        val clinicB = Clinic(id = 11L, name = "Beta")
        val userA = doctorUser(1L)
        val userB = doctorUser(2L)
        val docA = DoctorProfile(id = 101L, user = userA, firstName = "A", lastName = "One", practiceClinic = clinicA)
        val docB = DoctorProfile(id = 102L, user = userB, firstName = "B", lastName = "Two", practiceClinic = clinicB)

        `when`(doctors.findById(102L)).thenReturn(Optional.of(docB))

        assertFalse(service.canViewDoctorCalendar(doctorPrincipal(docA), 102L))
    }

    @Test
    fun `clinic staff may view doctor calendar for assigned clinic`() {
        val clinic = Clinic(id = 10L, name = "Alpha")
        val docUser = doctorUser(2L)
        val doc = DoctorProfile(id = 102L, user = docUser, firstName = "B", lastName = "Two", practiceClinic = clinic)

        val staffUser = User(id = 99L, passwordHash = "x", role = Role.CLINIC_STAFF, enabled = true)
        val membership = ClinicMembership(clinic = clinic, user = staffUser, membershipRole = ClinicMembership.MembershipRole.STAFF)
        val staffPrincipal = ClinicStaffPrincipal(
            staffUser,
            listOf(membership),
            listOf(SimpleGrantedAuthority("ROLE_CLINIC_STAFF")),
        )

        `when`(doctors.findById(102L)).thenReturn(Optional.of(doc))

        assertTrue(service.canViewDoctorCalendar(staffPrincipal, 102L))
    }

    @Test
    fun `patient directory for doctor is only self not all clinic doctors`() {
        val clinic = Clinic(id = 10L, name = "Alpha")
        val userA = doctorUser(1L)
        val docA = DoctorProfile(id = 101L, user = userA, firstName = "A", lastName = "One", practiceClinic = clinic)

        val ids = service.doctorIdsForPatientDirectory(doctorPrincipal(docA))
        assertEquals(listOf(101L), ids)
    }

    @Test
    fun `assertPatientLinkedToClinic allows patient created by clinic doctor`() {
        val clinic = Clinic(id = 10L, name = "Alpha")
        val userA = doctorUser(1L)
        val docA = DoctorProfile(
            id = 101L,
            user = userA,
            firstName = "A",
            lastName = "One",
            practiceClinic = clinic,
        )
        val patient = PatientProfile(id = 555L, fullName = "Pat", createdByDoctor = docA)

        `when`(doctors.findAllByPracticeClinic_Id(10L)).thenReturn(listOf(docA))
        `when`(patients.findById(555L)).thenReturn(Optional.of(patient))

        service.assertPatientLinkedToClinic(doctorPrincipal(docA), 555L, 10L)
    }

    @Test
    fun `assertPatientLinkedToClinic allows patient with non-cancelled appointment to clinic doctor`() {
        val clinic = Clinic(id = 10L, name = "Alpha")
        val userA = doctorUser(1L)
        val userB = doctorUser(2L)
        val docA = DoctorProfile(
            id = 101L,
            user = userA,
            firstName = "A",
            lastName = "One",
            practiceClinic = clinic,
        )
        val docB = DoctorProfile(
            id = 102L,
            user = userB,
            firstName = "B",
            lastName = "Two",
            practiceClinic = clinic,
        )
        val patient = PatientProfile(id = 555L, fullName = "Pat", createdByDoctor = docB)

        `when`(doctors.findAllByPracticeClinic_Id(10L)).thenReturn(listOf(docA, docB))
        `when`(patients.findById(555L)).thenReturn(Optional.of(patient))
        `when`(
            appointments.existsNonCancelledByPatientIdAndDoctorIds(555L, listOf(101L, 102L)),
        ).thenReturn(true)

        service.assertPatientLinkedToClinic(doctorPrincipal(docA), 555L, 10L)
    }

    @Test
    fun `assertPatientLinkedToClinic denies when patient not linked to clinic roster`() {
        val clinic = Clinic(id = 10L, name = "Alpha")
        val otherClinic = Clinic(id = 99L, name = "Other")
        val userA = doctorUser(1L)
        val userZ = doctorUser(9L)
        val docA = DoctorProfile(
            id = 101L,
            user = userA,
            firstName = "A",
            lastName = "One",
            practiceClinic = clinic,
        )
        val outsideDoc = DoctorProfile(
            id = 999L,
            user = userZ,
            firstName = "Z",
            lastName = "Out",
            practiceClinic = otherClinic,
        )
        val patient = PatientProfile(id = 555L, fullName = "Pat", createdByDoctor = outsideDoc)

        `when`(doctors.findAllByPracticeClinic_Id(10L)).thenReturn(listOf(docA))
        `when`(patients.findById(555L)).thenReturn(Optional.of(patient))
        `when`(appointments.existsNonCancelledByPatientIdAndDoctorIds(555L, listOf(101L))).thenReturn(false)

        val ex = assertThrows(ResponseStatusException::class.java) {
            service.assertPatientLinkedToClinic(doctorPrincipal(docA), 555L, 10L)
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }
}
