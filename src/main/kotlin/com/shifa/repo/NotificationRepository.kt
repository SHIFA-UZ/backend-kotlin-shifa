package com.shifa.repo

import com.shifa.domain.Notification
import org.springframework.data.jpa.repository.JpaRepository

interface NotificationRepository : JpaRepository<Notification, Long> {
    fun findByPatient_IdOrderByCreatedAtDesc(patientId: Long): List<Notification>
    fun findByDoctor_IdOrderByCreatedAtDesc(doctorId: Long): List<Notification>

    fun findByPatient_IdAndAppointmentIdAndType(
        patientId: Long,
        appointmentId: Long,
        type: Notification.Type
    ): List<Notification>

    fun existsByPatient_IdAndTreatmentPlanIdAndType(
        patientId: Long,
        treatmentPlanId: Long,
        type: Notification.Type
    ): Boolean

    fun findFirstByTreatmentPlanIdAndTypeOrderByCreatedAtDesc(
        treatmentPlanId: Long,
        type: Notification.Type
    ): Notification?
}
