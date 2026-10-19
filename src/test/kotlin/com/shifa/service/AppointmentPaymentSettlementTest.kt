package com.shifa.service

import com.shifa.domain.Appointment
import com.shifa.domain.DoctorProfile
import com.shifa.domain.PatientProfile
import com.shifa.domain.User
import com.shifa.payment.domain.Payment
import com.shifa.payment.domain.PaymentGatewayCode
import com.shifa.payment.domain.PaymentKind
import com.shifa.payment.domain.PaymentStatus
import com.shifa.payment.repo.PaymentRepository
import com.shifa.repo.AppointmentRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.Optional

class AppointmentPaymentSettlementTest {
    private val appointments = mock(AppointmentRepository::class.java)
    private val payments = mock(PaymentRepository::class.java)
    private val settlement = AppointmentPaymentSettlement(appointments, payments)
    private val doctor = DoctorProfile(
        id = 5,
        user = User(id = 1, passwordHash = "x"),
        firstName = "Ada",
        lastName = "Lovelace",
    )
    private val patient = PatientProfile(id = 3L, fullName = "Franco")

    private fun visit(status: Appointment.Status, id: Long = 7L) = Appointment(
        id = id,
        doctor = doctor,
        patient = patient,
        startAt = Instant.parse("2026-07-31T12:00:00Z"),
        endAt = Instant.parse("2026-07-31T12:30:00Z"),
        location = "Clinic",
        status = status,
        paymentStatus = Appointment.PaymentStatus.PENDING,
    )

    @Test
    fun `webhook success confirms a payment hold`() {
        val apt = visit(Appointment.Status.PENDING_PAYMENT)
        `when`(appointments.findByIdForUpdate(7L)).thenReturn(Optional.of(apt))
        `when`(appointments.save(any(Appointment::class.java) ?: apt)).thenAnswer { it.arguments[0] }

        settlement.onGatewayPaid(7L)

        assertEquals(Appointment.Status.CONFIRMED, apt.status)
        assertEquals(Appointment.PaymentStatus.PAID, apt.paymentStatus)
        assertFalse(apt.refundRequired)
    }

    @Test
    fun `late payment reconfirms when the slot is free and flags a refund when it is taken`() {
        val expired = visit(Appointment.Status.EXPIRED)
        expired.paymentStatus = Appointment.PaymentStatus.FAILED
        `when`(appointments.findByIdForUpdate(7L)).thenReturn(Optional.of(expired))
        `when`(appointments.findOverlapping(5L, expired.startAt, expired.endAt)).thenReturn(emptyList())
        `when`(appointments.save(any(Appointment::class.java) ?: expired)).thenAnswer { it.arguments[0] }

        settlement.onGatewayPaid(7L)
        assertEquals(Appointment.Status.CONFIRMED, expired.status)
        assertFalse(expired.refundRequired)

        expired.status = Appointment.Status.EXPIRED
        val other = visit(Appointment.Status.CONFIRMED, id = 8L)
        `when`(appointments.findOverlapping(5L, expired.startAt, expired.endAt)).thenReturn(listOf(other))
        settlement.onGatewayPaid(7L)
        assertEquals(Appointment.Status.EXPIRED, expired.status)
        assertTrue(expired.refundRequired)
        assertEquals(Appointment.PaymentStatus.PAID, expired.paymentStatus)
    }

    @Test
    fun `failure and hold expiry release the slot`() {
        val apt = visit(Appointment.Status.PENDING_PAYMENT)
        apt.holdExpiresAt = Instant.parse("2026-07-31T10:00:00Z")
        val payment = Payment(
            externalRef = "ref",
            gateway = PaymentGatewayCode.CLICK,
            kind = PaymentKind.CONSULTATION,
            amountMinor = 300000,
            currency = "UZS",
            appointment = apt,
        )
        `when`(appointments.findExpiredPaymentHolds(any(Instant::class.java) ?: Instant.EPOCH)).thenReturn(listOf(apt))
        `when`(appointments.findByIdForUpdate(7L)).thenReturn(Optional.of(apt))
        `when`(appointments.save(any(Appointment::class.java) ?: apt)).thenAnswer { it.arguments[0] }
        `when`(payments.findByAppointment_Id(7L)).thenReturn(payment)
        `when`(payments.save(any(Payment::class.java) ?: payment)).thenAnswer { it.arguments[0] }

        val released = settlement.expireDueHolds(Instant.parse("2026-07-31T10:16:00Z"))

        assertEquals(1, released)
        assertEquals(Appointment.Status.EXPIRED, apt.status)
        assertEquals(Appointment.PaymentStatus.FAILED, apt.paymentStatus)
        assertEquals(PaymentStatus.CANCELLED, payment.status)
        verify(appointments, times(1)).save(apt)
    }
}
