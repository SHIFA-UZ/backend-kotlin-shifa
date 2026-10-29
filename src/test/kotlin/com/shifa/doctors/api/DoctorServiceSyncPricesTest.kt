package com.shifa.doctors.api


import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.doctors.domain.DoctorService
import com.shifa.shared.security.DoctorPrincipal
import java.util.Optional
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.springframework.security.core.authority.SimpleGrantedAuthority

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
        val services = mock(com.shifa.doctors.application.DoctorServices::class.java)
        val prices = mock(com.shifa.doctors.application.DoctorServicePrices::class.java)
        val locations = mock(com.shifa.doctors.application.DoctorLocations::class.java)
        val groups = mock(com.shifa.doctors.application.DoctorServiceGroups::class.java)
        `when`(services.findById(28L)).thenReturn(Optional.of(service))
        `when`(prices.deleteByService_Id(28L)).thenReturn(1L)

        val controller = DoctorServiceController(services, prices, locations, groups)
        val principal = DoctorPrincipal(profile, listOf(SimpleGrantedAuthority("ROLE_DOCTOR")))
        controller.update(
            principal,
            28L,
            com.shifa.doctors.api.dto.UpsertServiceRequest(
                title = "Consult",
                isActive = true,
                isFreeConsultation = false,
                prices = listOf(
                    com.shifa.doctors.api.dto.PriceDto(amountMinor = 5000L, currency = "UZS"),
                ),
            ),
        )

        val order = inOrder(prices)
        order.verify(prices).deleteByService_Id(28L)
        order.verify(prices).flush()
    }
}
