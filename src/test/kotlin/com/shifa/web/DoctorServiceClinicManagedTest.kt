package com.shifa.web

import com.shifa.domain.Clinic
import com.shifa.domain.DoctorProfile
import com.shifa.domain.DoctorService
import com.shifa.domain.Role
import com.shifa.domain.TreatmentPlanCatalogItem
import com.shifa.domain.User
import com.shifa.repo.DoctorLocationRepository
import com.shifa.repo.DoctorServiceGroupRepository
import com.shifa.repo.DoctorServicePriceRepository
import com.shifa.repo.DoctorServiceRepository
import com.shifa.security.DoctorPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.web.server.ResponseStatusException
import java.util.Optional

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
        val services = mock(DoctorServiceRepository::class.java)
        `when`(services.findById(7L)).thenReturn(Optional.of(managed))
        val prices = mock(DoctorServicePriceRepository::class.java)
        val locations = mock(DoctorLocationRepository::class.java)
        val groups = mock(DoctorServiceGroupRepository::class.java)
        val controller = DoctorServiceController(services, prices, locations, groups)
        val principal = DoctorPrincipal(profile, listOf(SimpleGrantedAuthority("ROLE_DOCTOR")))
        val ex = assertThrows<ResponseStatusException> {
            controller.update(
                principal,
                7L,
                DoctorServiceController.UpsertServiceRequest(
                    title = "Changed",
                    isActive = true,
                    isFreeConsultation = false,
                    prices = listOf(DoctorServiceController.PriceDto(amountMinor = 5000L, currency = "UZS")),
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
        val services = mock(DoctorServiceRepository::class.java)
        `when`(services.findById(7L)).thenReturn(Optional.of(managed))
        val controller = DoctorServiceController(
            services,
            mock(DoctorServicePriceRepository::class.java),
            mock(DoctorLocationRepository::class.java),
            mock(DoctorServiceGroupRepository::class.java),
        )
        val principal = DoctorPrincipal(profile, listOf(SimpleGrantedAuthority("ROLE_DOCTOR")))
        val ex = assertThrows<ResponseStatusException> {
            controller.delete(principal, 7L)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }
}
