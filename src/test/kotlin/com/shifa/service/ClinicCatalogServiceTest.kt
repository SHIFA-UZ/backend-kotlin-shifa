package com.shifa.service

import com.shifa.domain.Clinic
import com.shifa.domain.DoctorProfile
import com.shifa.domain.Role
import com.shifa.domain.User
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.DoctorServicePriceRepository
import com.shifa.repo.DoctorServiceRepository
import com.shifa.repo.TreatmentPlanCatalogItemDoctorRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class ClinicCatalogServiceTest {

    @Test
    fun `validateAssignment requires doctors when not all-doctors mode`() {
        val doctors = mock(DoctorProfileRepository::class.java)
        val links = mock(TreatmentPlanCatalogItemDoctorRepository::class.java)
        val doctorServices = mock(DoctorServiceRepository::class.java)
        val prices = mock(DoctorServicePriceRepository::class.java)
        val svc = ClinicCatalogService(doctors, links, doctorServices, prices)
        `when`(doctors.findAllByPracticeClinic_Id(1L)).thenReturn(
            listOf(doc(10L, 1L)),
        )
        val ex = assertThrows<ResponseStatusException> {
            svc.validateAssignment(1L, false, emptyList())
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `validateAssignment rejects ids outside practice clinic`() {
        val doctors = mock(DoctorProfileRepository::class.java)
        val svc = ClinicCatalogService(
            doctors,
            mock(TreatmentPlanCatalogItemDoctorRepository::class.java),
            mock(DoctorServiceRepository::class.java),
            mock(DoctorServicePriceRepository::class.java),
        )
        `when`(doctors.findAllByPracticeClinic_Id(1L)).thenReturn(listOf(doc(10L, 1L)))
        val ex = assertThrows<ResponseStatusException> {
            svc.validateAssignment(1L, false, listOf(99L))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    private fun doc(profileId: Long, userId: Long): DoctorProfile {
        val clinic = Clinic(id = 1L, name = "C")
        val user = User(id = userId, passwordHash = "x", role = Role.DOCTOR, enabled = true)
        return DoctorProfile(
            id = profileId,
            user = user,
            firstName = "A",
            lastName = "B",
            practiceClinic = clinic,
        )
    }
}
