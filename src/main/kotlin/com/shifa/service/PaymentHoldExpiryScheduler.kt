package com.shifa.service

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class PaymentHoldExpiryScheduler(
    private val settlement: AppointmentPaymentSettlement,
) {
    @Scheduled(fixedDelay = 60_000)
    fun expireHolds() {
        settlement.expireDueHolds(Instant.now())
    }
}
