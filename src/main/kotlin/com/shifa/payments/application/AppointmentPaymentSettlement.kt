package com.shifa.payments.application

import com.shifa.appointments.domain.Appointment
import com.shifa.payments.domain.OpenCheckoutPolicy
import com.shifa.payments.domain.PaymentStatus
import com.shifa.payments.infrastructure.PaymentRepository
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Applies gateway results to an appointment. Payment success never comes from the browser redirect.
 */
@Service
class AppointmentPaymentSettlement(
    private val appointments: com.shifa.appointments.application.Appointments,
    private val payments: PaymentRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun onGatewayPaid(appointmentId: Long) {
        val apt = appointments.findByIdForUpdate(appointmentId).orElse(null) ?: return
        apt.paymentStatus = Appointment.PaymentStatus.PAID
        apt.holdExpiresAt = null
        when (apt.status) {
            Appointment.Status.IN_PROGRESS,
            Appointment.Status.COMPLETED -> Unit
            Appointment.Status.CANCELLED -> {
                apt.refundRequired = true
                log.warn("Payment succeeded after cancel; refund required appointment {}", apt.id)
            }
            Appointment.Status.EXPIRED,
            Appointment.Status.PAYMENT_FAILED -> {
                val taken = appointments.findOverlapping(apt.doctor.id!!, apt.startAt, apt.endAt)
                    .any { it.id != apt.id }
                if (taken) {
                    apt.refundRequired = true
                    log.warn("Late payment slot taken; refund required appointment {}", apt.id)
                } else {
                    apt.status = Appointment.Status.CONFIRMED
                    apt.refundRequired = false
                    log.info("Late payment reconfirmed appointment {} because the slot was free", apt.id)
                }
            }
            else -> apt.status = Appointment.Status.CONFIRMED
        }
        appointments.save(apt)
    }

    @Transactional
    fun onGatewayFailed(appointmentId: Long, expired: Boolean) {
        val apt = appointments.findByIdForUpdate(appointmentId).orElse(null) ?: return
        if (apt.paymentStatus == Appointment.PaymentStatus.PAID) return
        if (apt.status == Appointment.Status.CANCELLED ||
            apt.status == Appointment.Status.COMPLETED ||
            apt.status == Appointment.Status.IN_PROGRESS
        ) {
            return
        }
        apt.paymentStatus = Appointment.PaymentStatus.FAILED
        apt.holdExpiresAt = null
        apt.status = if (expired) Appointment.Status.EXPIRED else Appointment.Status.PAYMENT_FAILED
        appointments.save(apt)
    }

    @Transactional
    fun expireDueHolds(now: Instant): Int {
        var released = 0
        for (row in appointments.findExpiredPaymentHolds(now)) {
            val apt = appointments.findByIdForUpdate(row.id).orElse(null) ?: continue
            if (apt.status != Appointment.Status.PENDING_PAYMENT) continue
            if (apt.paymentStatus == Appointment.PaymentStatus.PAID) continue
            val exp = apt.holdExpiresAt ?: continue
            if (!exp.isBefore(now)) continue
            onGatewayFailed(apt.id, expired = true)
            payments.findByAppointment_IdAndStatusInOrderByCreatedAtDesc(apt.id, OpenCheckoutPolicy.OPEN_STATUSES)
                .forEach { payment ->
                    payment.status = PaymentStatus.CANCELLED
                    payment.failureReason = "Payment hold expired"
                    payment.updatedAt = now
                    payments.save(payment)
                }
            released++
        }
        if (released > 0) log.info("Released {} expired payment holds", released)
        return released
    }
}
