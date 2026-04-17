package com.shifa.repo

import com.shifa.domain.DoctorBilling
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface DoctorBillingRepository : JpaRepository<DoctorBilling, Long> {
    fun findByDoctorId(doctorId: Long): Optional<DoctorBilling>
}
