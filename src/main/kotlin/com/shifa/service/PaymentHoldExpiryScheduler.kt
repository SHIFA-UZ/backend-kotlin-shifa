package com.shifa.service

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class PaymentHoldExpiryScheduler(
    private val settlement: AppointmentPaymentSettlement,
) {
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "payment-hold-expiry", lockAtLeastFor = "PT20S")
    fun expireHolds() {
        settlement.expireDueHolds(Instant.now())
    }
}
