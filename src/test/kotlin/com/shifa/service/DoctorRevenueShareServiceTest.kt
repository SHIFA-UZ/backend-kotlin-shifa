package com.shifa.service

import com.shifa.domain.Clinic
import com.shifa.domain.ClinicMembership
import com.shifa.domain.DoctorProfile
import com.shifa.domain.User
import com.shifa.repo.ClinicMembershipRepository
import com.shifa.repo.ClinicRepository
import com.shifa.repo.DoctorProfileRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional

class DoctorRevenueShareServiceTest {

    private val clinics = mock(ClinicRepository::class.java)
    private val memberships = mock(ClinicMembershipRepository::class.java)
    private val doctors = mock(DoctorProfileRepository::class.java)
    private val service = DoctorRevenueShareService(clinics, memberships, doctors)

    @Test
    fun `splitAmount uses integer minor units and assigns remainder to clinic`() {
        val (doctor, clinic) = DoctorRevenueShareService.splitAmount(1_000L, 40)
        assertEquals(400L, doctor)
        assertEquals(600L, clinic)

        val (doctorOdd, clinicOdd) = DoctorRevenueShareService.splitAmount(1_001L, 40)
        assertEquals(400L, doctorOdd)
        assertEquals(601L, clinicOdd)
    }

    @Test
    fun `applySplit fills gross and collected share fields`() {
        val agg = ClinicFinanceLedgerService.DoctorEarningAgg(
            doctorProfileId = 16L,
            visitCount = 2,
            grossMinor = 1_000L,
            collectedMinor = 750L,
            outstandingMinor = 250L,
        )
        val split = service.applySplit(agg, 40)
        assertEquals(40, split.revenueSharePercent)
        assertEquals(400L, split.doctorShareGrossMinor)
        assertEquals(600L, split.clinicShareGrossMinor)
        assertEquals(300L, split.doctorShareCollectedMinor)
        assertEquals(450L, split.clinicShareCollectedMinor)
        assertEquals(
            split.grossMinor,
            (split.doctorShareGrossMinor ?: 0L) + (split.clinicShareGrossMinor ?: 0L),
        )
        assertEquals(
            split.collectedMinor,
            (split.doctorShareCollectedMinor ?: 0L) + (split.clinicShareCollectedMinor ?: 0L),
        )
    }

    @Test
    fun `loadEffectiveShare prefers membership override over clinic default`() {
        val clinic = Clinic(name = "SEBAMED")
        clinic.defaultDoctorRevenueSharePercent = 40
        `when`(clinics.findById(1L)).thenReturn(Optional.of(clinic))

        val user = mock(User::class.java)
        `when`(user.id).thenReturn(99L)
        val doctor = mock(DoctorProfile::class.java)
        `when`(doctor.id).thenReturn(16L)
        `when`(doctor.user).thenReturn(user)
        `when`(doctors.findAllByPracticeClinic_Id(1L)).thenReturn(listOf(doctor))

        val membership = mock(ClinicMembership::class.java)
        `when`(membership.active).thenReturn(true)
        `when`(membership.doctorRevenueSharePercent).thenReturn(50)
        `when`(memberships.findByClinic_IdAndUser_Id(1L, 99L)).thenReturn(membership)

        val map = service.loadEffectiveShareByDoctorProfileId(1L)
        assertEquals(50, map[16L])
    }

    @Test
    fun `loadEffectiveShare omits doctors without configured percent`() {
        val clinic = Clinic(name = "SEBAMED")
        clinic.defaultDoctorRevenueSharePercent = null
        `when`(clinics.findById(1L)).thenReturn(Optional.of(clinic))

        val user = mock(User::class.java)
        `when`(user.id).thenReturn(99L)
        val doctor = mock(DoctorProfile::class.java)
        `when`(doctor.id).thenReturn(16L)
        `when`(doctor.user).thenReturn(user)
        `when`(doctors.findAllByPracticeClinic_Id(1L)).thenReturn(listOf(doctor))

        val membership = mock(ClinicMembership::class.java)
        `when`(membership.active).thenReturn(true)
        `when`(membership.doctorRevenueSharePercent).thenReturn(null)
        `when`(memberships.findByClinic_IdAndUser_Id(1L, 99L)).thenReturn(membership)

        val map = service.loadEffectiveShareByDoctorProfileId(1L)
        assertNull(map[16L])
        assertEquals(emptyMap<Long, Int>(), map)
    }
}
