package com.shifa.appointments.domain

import com.shifa.doctors.domain.DoctorProfile
import com.shifa.doctors.domain.DoctorService
import com.shifa.doctors.domain.ServicePaymentType
import com.shifa.auth.domain.User
import com.shifa.payments.domain.PaymentGatewayCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class BookingPaymentPolicyTest {
    private val doctor = DoctorProfile(
        id = 1,
        user = User(id = 1, passwordHash = "x"),
        firstName = "Azamat",
        lastName = "Rakhimov",
    )

    private fun service(free: Boolean, type: ServicePaymentType) = DoctorService(
        doctor = doctor,
        title = "Check up",
        isFreeConsultation = free,
        paymentType = type,
    )

    @Test
    fun `free service allows video and has no payment badge`() {
        val free = service(free = true, type = ServicePaymentType.PAY_NOW)
        assertTrue(BookingPaymentPolicy.videoAllowed(free))
        assertEquals(BookingPaymentPolicy.Kind.FREE, BookingPaymentPolicy.kind(free))
        assertNull(BookingPaymentPolicy.paymentBadge(com.shifa.appointments.domain.Appointment.PaymentStatus.NOT_REQUIRED))
    }

    @Test
    fun `pay later rejects video and badges pay in clinic`() {
        val later = service(free = false, type = ServicePaymentType.PAY_LATER)
        assertFalse(BookingPaymentPolicy.videoAllowed(later))
        assertEquals(BookingPaymentPolicy.Kind.PAY_LATER, BookingPaymentPolicy.kind(later))
        assertEquals("PAY_IN_CLINIC", BookingPaymentPolicy.paymentBadge(com.shifa.appointments.domain.Appointment.PaymentStatus.PAY_IN_CLINIC))
    }

    @Test
    fun `pay now allows video and badges paid`() {
        val now = service(free = false, type = ServicePaymentType.PAY_NOW)
        assertTrue(BookingPaymentPolicy.videoAllowed(now))
        assertEquals(BookingPaymentPolicy.Kind.PAY_NOW, BookingPaymentPolicy.kind(now))
        assertEquals("PAID", BookingPaymentPolicy.paymentBadge(com.shifa.appointments.domain.Appointment.PaymentStatus.PAID))
    }

    @Test
    fun `inside 24 hours blocks cancel and outside allows it`() {
        val now = Instant.parse("2026-07-31T07:00:00Z")
        val soon = Instant.parse("2026-07-31T20:00:00Z")
        val later = Instant.parse("2026-08-02T07:00:00Z")
        assertTrue(BookingPaymentPolicy.isLocked24h(soon, now))
        assertFalse(BookingPaymentPolicy.canPatientChange(com.shifa.appointments.domain.Appointment.Status.CONFIRMED, locked = true))
        assertFalse(BookingPaymentPolicy.isLocked24h(later, now))
        assertTrue(BookingPaymentPolicy.canPatientChange(com.shifa.appointments.domain.Appointment.Status.CONFIRMED, locked = false))
    }

    @Test
    fun `one ready provider is a single method and two are a choice`() {
        val both = BookingPaymentPolicy.readyProviders(
            listOf(PaymentGatewayCode.CLICK, PaymentGatewayCode.STRIPE),
            clickReady = true,
            stripeReady = true,
        )
        val onlyClick = BookingPaymentPolicy.readyProviders(
            listOf(PaymentGatewayCode.CLICK, PaymentGatewayCode.STRIPE),
            clickReady = true,
            stripeReady = false,
        )
        assertEquals(listOf(PaymentGatewayCode.CLICK, PaymentGatewayCode.STRIPE), both)
        assertEquals(listOf(PaymentGatewayCode.CLICK), onlyClick)
        assertNull(BookingPaymentPolicy.chooseProvider(null, onlyClick))
        assertNull(BookingPaymentPolicy.chooseProvider(null, both))
        assertEquals(PaymentGatewayCode.STRIPE, BookingPaymentPolicy.chooseProvider("STRIPE", both))
        assertThrows(IllegalArgumentException::class.java) {
            BookingPaymentPolicy.chooseProvider("PAYPAL", both)
        }
    }
}
