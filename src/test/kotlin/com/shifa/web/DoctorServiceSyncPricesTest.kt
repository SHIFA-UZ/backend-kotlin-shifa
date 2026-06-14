package com.shifa.web

import com.shifa.domain.DoctorProfile
import com.shifa.domain.DoctorService
import com.shifa.domain.Role
import com.shifa.domain.User
import com.shifa.repo.DoctorLocationRepository
import com.shifa.repo.DoctorServiceGroupRepository
import com.shifa.repo.DoctorServicePriceRepository
import com.shifa.repo.DoctorServiceRepository
import com.shifa.security.DoctorPrincipal
import org.junit.jupiter.api.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.util.Optional

class DoctorServiceSyncPricesTest {

    @Test
    fun `update flushes price deletes before inserting new rows`() {
        val user = User(id = 1L, passwordHash = "x", role = Role.DOCTOR, enabled = true)
        val profile = DoctorProfile(id = 10L, user = user, firstName = "A", lastName = "B")
        val service = DoctorService(
            id = 28L,
            doctor = profile,
            title = "Consult",
            isActive = true,
            isFreeConsultation = false,
        )
        val services = mock(DoctorServiceRepository::class.java)
        val prices = mock(DoctorServicePriceRepository::class.java)
        val locations = mock(DoctorLocationRepository::class.java)
        val groups = mock(DoctorServiceGroupRepository::class.java)
        `when`(services.findById(28L)).thenReturn(Optional.of(service))
        `when`(prices.deleteByService_Id(28L)).thenReturn(1L)

        val controller = DoctorServiceController(services, prices, locations, groups)
        val principal = DoctorPrincipal(profile, listOf(SimpleGrantedAuthority("ROLE_DOCTOR")))
        controller.update(
            principal,
            28L,
            DoctorServiceController.UpsertServiceRequest(
                title = "Consult",
                isActive = true,
                isFreeConsultation = false,
                prices = listOf(
                    DoctorServiceController.PriceDto(amountMinor = 5000L, currency = "UZS"),
                ),
            ),
        )

        val order = inOrder(prices)
        order.verify(prices).deleteByService_Id(28L)
        order.verify(prices).flush()
    }
}
