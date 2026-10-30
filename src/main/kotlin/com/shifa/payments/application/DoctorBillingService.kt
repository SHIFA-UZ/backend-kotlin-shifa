package com.shifa.payments.application

import com.shifa.payments.domain.DoctorBilling
import com.shifa.payments.infrastructure.DoctorBillingRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Optional

/** The door to a doctor's billing details for the doctors and auth features (same names as the former repository calls). */
@Service
class DoctorBillingService(private val billing: DoctorBillingRepository) {
    @Transactional(readOnly = true)
    fun findByDoctorId(doctorId: Long): Optional<DoctorBilling> = billing.findByDoctorId(doctorId)
    @Transactional
    fun save(doctorBilling: DoctorBilling): DoctorBilling = billing.save(doctorBilling)
    @Transactional
    fun deleteForDoctor(doctorId: Long) = billing.findByDoctorId(doctorId).ifPresent { billing.delete(it) }
}
