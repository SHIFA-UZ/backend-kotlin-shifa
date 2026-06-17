package com.shifa.repo

import com.shifa.domain.TreatmentPlanAppointmentLink
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TreatmentPlanAppointmentLinkRepository : JpaRepository<TreatmentPlanAppointmentLink, Long> {
    fun findByPlan_IdAndAppointment_Id(planId: Long, appointmentId: Long): TreatmentPlanAppointmentLink?

    fun findByAppointment_Id(appointmentId: Long): List<TreatmentPlanAppointmentLink>

    @Query(
        """
        SELECT CASE WHEN COUNT(l) > 0 THEN true ELSE false END
        FROM TreatmentPlanAppointmentLink l
        JOIN l.plan p
        WHERE l.appointment.id = :appointmentId AND p.planKind = com.shifa.domain.TreatmentPlan.PlanKind.COMPREHENSIVE
        """,
    )
    fun existsComprehensiveLinkForAppointment(@Param("appointmentId") appointmentId: Long): Boolean
}
