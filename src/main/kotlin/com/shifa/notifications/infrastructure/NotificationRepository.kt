package com.shifa.notifications.infrastructure

import com.shifa.notifications.domain.Notification
import org.springframework.data.jpa.repository.JpaRepository

interface NotificationRepository : JpaRepository<Notification, Long> {
    fun findByPatient_IdOrderByCreatedAtDesc(patientId: Long): List<Notification>
    fun findByDoctor_IdOrderByCreatedAtDesc(doctorId: Long): List<Notification>

    fun findByPatient_IdAndAppointmentIdAndType(
        patientId: Long,
        appointmentId: Long,
        type: Notification.Type
    ): List<Notification>

    fun existsByPatient_IdAndAppointmentIdAndType(
        patientId: Long,
        appointmentId: Long,
        type: Notification.Type
    ): Boolean

    fun existsByDoctor_IdAndAppointmentIdAndType(
        doctorId: Long,
        appointmentId: Long,
        type: Notification.Type
    ): Boolean

    fun existsByPatient_IdAndTreatmentPlanIdAndType(
        patientId: Long,
        treatmentPlanId: Long,
        type: Notification.Type
    ): Boolean

    fun findFirstByTreatmentPlanIdAndTypeOrderByCreatedAtDesc(
        treatmentPlanId: Long,
        type: Notification.Type
    ): Notification?

    fun existsByPatient_IdAndInstallmentItemIdAndType(
        patientId: Long,
        installmentItemId: Long,
        type: Notification.Type
    ): Boolean

    @Suppress("FunctionNaming")
    fun countByDoctor_IdAndReadAtIsNull(doctorId: Long): Long
    @Suppress("FunctionNaming")
    fun countByPatient_IdAndReadAtIsNull(patientId: Long): Long

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE Notification n SET n.readAt = :now WHERE n.doctor.id = :doctorId AND n.readAt IS NULL")
    fun markAllReadForDoctor(
        @org.springframework.data.repository.query.Param("doctorId") doctorId: Long,
        @org.springframework.data.repository.query.Param("now") now: java.time.Instant,
    ): Int

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE Notification n SET n.readAt = :now WHERE n.patient.id = :patientId AND n.readAt IS NULL")
    fun markAllReadForPatient(
        @org.springframework.data.repository.query.Param("patientId") patientId: Long,
        @org.springframework.data.repository.query.Param("now") now: java.time.Instant,
    ): Int
}
