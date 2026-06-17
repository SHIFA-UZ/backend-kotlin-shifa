package com.shifa.repo

import com.shifa.domain.TreatmentPlanLineFulfillment
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TreatmentPlanLineFulfillmentRepository : JpaRepository<TreatmentPlanLineFulfillment, Long> {
    fun existsByLine_Id(lineId: Long): Boolean

    fun findByAppointment_Id(appointmentId: Long): List<TreatmentPlanLineFulfillment>

    fun findByLine_Id(lineId: Long): TreatmentPlanLineFulfillment?

    /** Scalar projection — avoids lazy-loading [TreatmentPlanLine] on hot poll paths. */
    @Query(
        """
        SELECT f.line.id FROM TreatmentPlanLineFulfillment f
        WHERE f.appointment.id = :appointmentId
        """,
    )
    fun findLineIdsByAppointmentId(@Param("appointmentId") appointmentId: Long): List<Long>

    @Query(
        """
        SELECT f.line.id FROM TreatmentPlanLineFulfillment f
        WHERE f.line.id IN :lineIds
        """,
    )
    fun findFulfilledLineIdsIn(@Param("lineIds") lineIds: Collection<Long>): List<Long>

    @Query(
        """
        SELECT f FROM TreatmentPlanLineFulfillment f
        JOIN FETCH f.line l
        JOIN FETCH l.plan
        WHERE f.appointment.id = :appointmentId
        """,
    )
    fun findByAppointmentIdWithLineAndPlan(
        @Param("appointmentId") appointmentId: Long,
    ): List<TreatmentPlanLineFulfillment>
}
