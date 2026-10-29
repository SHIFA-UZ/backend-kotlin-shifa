package com.shifa.clinics.application

import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import com.shifa.clinics.domain.Clinic
import com.shifa.clinics.infrastructure.TreatmentPlanCatalogItemDoctorRepository
import com.shifa.doctors.domain.DoctorProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class ClinicCatalogServiceTest {

    @Test
    fun `validateAssignment requires doctors when not all-doctors mode`() {
        val doctors = mock(com.shifa.doctors.application.DoctorProfiles::class.java)
        val links = mock(TreatmentPlanCatalogItemDoctorRepository::class.java)
        val doctorServices = mock(com.shifa.doctors.application.DoctorServices::class.java)
        val prices = mock(com.shifa.doctors.application.DoctorServicePrices::class.java)
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
        val doctors = mock(com.shifa.doctors.application.DoctorProfiles::class.java)
        val svc = ClinicCatalogService(
            doctors,
            mock(TreatmentPlanCatalogItemDoctorRepository::class.java),
            mock(com.shifa.doctors.application.DoctorServices::class.java),
            mock(com.shifa.doctors.application.DoctorServicePrices::class.java),
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
