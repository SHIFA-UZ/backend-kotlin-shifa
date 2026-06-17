package com.shifa.repo

import com.shifa.domain.TreatmentPlanLineFulfillment
import org.springframework.data.jpa.repository.JpaRepository

interface TreatmentPlanLineFulfillmentRepository : JpaRepository<TreatmentPlanLineFulfillment, Long> {
    fun existsByLine_Id(lineId: Long): Boolean

    fun findByAppointment_Id(appointmentId: Long): List<TreatmentPlanLineFulfillment>

    fun findByLine_Id(lineId: Long): TreatmentPlanLineFulfillment?
}
