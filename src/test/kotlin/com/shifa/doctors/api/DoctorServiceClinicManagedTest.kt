package com.shifa.doctors.api


import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import com.shifa.clinics.domain.Clinic
import com.shifa.clinics.domain.TreatmentPlanCatalogItem
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.doctors.domain.DoctorService
import com.shifa.shared.security.DoctorPrincipal
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.http.HttpStatus
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.web.server.ResponseStatusException

class DoctorServiceClinicManagedTest {

    @Test
    fun `update returns forbidden for clinic-provisioned service`() {
        val clinic = Clinic(id = 1L, name = "C")
        val user = User(id = 1L, passwordHash = "x", role = Role.DOCTOR, enabled = true)
        val profile = DoctorProfile(
            id = 10L,
            user = user,
            firstName = "A",
            lastName = "B",
            practiceClinic = clinic,
        )
        val catalog = TreatmentPlanCatalogItem(
            id = 55L,
            clinic = clinic,
            title = "Catalog service",
            defaultPriceMinor = 1000L,
            currency = "UZS",
            appliesToAllDoctors = true,
        )
        val managed = DoctorService(
            doctor = profile,
            title = "Synced",
            isActive = true,
            isFreeConsultation = false,
            sourceCatalogItem = catalog,
        )
        val services = mock(com.shifa.doctors.application.DoctorServices::class.java)
        `when`(services.findById(7L)).thenReturn(Optional.of(managed))
        val prices = mock(com.shifa.doctors.application.DoctorServicePrices::class.java)
        val locations = mock(com.shifa.doctors.application.DoctorLocations::class.java)
        val groups = mock(com.shifa.doctors.application.DoctorServiceGroups::class.java)
        val controller = DoctorServiceController(services, prices, locations, groups)
        val principal = DoctorPrincipal(profile, listOf(SimpleGrantedAuthority("ROLE_DOCTOR")))
        val ex = assertThrows<ResponseStatusException> {
            controller.update(
                principal,
                7L,
                com.shifa.doctors.api.dto.UpsertServiceRequest(
                    title = "Changed",
                    isActive = true,
                    isFreeConsultation = false,
                    prices = listOf(com.shifa.doctors.api.dto.PriceDto(amountMinor = 5000L, currency = "UZS")),
                ),
            )
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `delete returns forbidden for clinic-provisioned service`() {
        val clinic = Clinic(id = 1L, name = "C")
        val user = User(id = 1L, passwordHash = "x", role = Role.DOCTOR, enabled = true)
        val profile = DoctorProfile(
            id = 10L,
            user = user,
            firstName = "A",
            lastName = "B",
            practiceClinic = clinic,
        )
        val catalog = TreatmentPlanCatalogItem(
            id = 55L,
            clinic = clinic,
            title = "Catalog service",
            defaultPriceMinor = 1000L,
            currency = "UZS",
            appliesToAllDoctors = true,
        )
        val managed = DoctorService(
            doctor = profile,
            title = "Synced",
            isActive = true,
            isFreeConsultation = false,
            sourceCatalogItem = catalog,
        )
        val services = mock(com.shifa.doctors.application.DoctorServices::class.java)
        `when`(services.findById(7L)).thenReturn(Optional.of(managed))
        val controller = DoctorServiceController(
            services,
            mock(com.shifa.doctors.application.DoctorServicePrices::class.java),
            mock(com.shifa.doctors.application.DoctorLocations::class.java),
            mock(com.shifa.doctors.application.DoctorServiceGroups::class.java),
        )
        val principal = DoctorPrincipal(profile, listOf(SimpleGrantedAuthority("ROLE_DOCTOR")))
        val ex = assertThrows<ResponseStatusException> {
            controller.delete(principal, 7L)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }
}
