package com.shifa.repo

import com.shifa.domain.PatientVisitFact
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface PatientVisitFactRepository : JpaRepository<PatientVisitFact, Long> {
    fun findByAppointmentIdOrderByCreatedAtAsc(appointmentId: Long): List<PatientVisitFact>

    fun findByPatientIdAndDoctorIdAndStatusOrderByCreatedAtDesc(
        patientId: Long,
        doctorId: Long,
        status: PatientVisitFact.Status,
    ): List<PatientVisitFact>

    fun findByPatientIdAndDoctorIdAndStatusInOrderByCreatedAtDesc(
        patientId: Long,
        doctorId: Long,
        statuses: Collection<PatientVisitFact.Status>,
    ): List<PatientVisitFact>

    fun findByAppointmentIdAndStatus(
        appointmentId: Long,
        status: PatientVisitFact.Status,
    ): List<PatientVisitFact>

    @Query(
        """
        SELECT f FROM PatientVisitFact f
        WHERE f.doctorId = :doctorId
          AND f.patientId = :patientId
          AND f.status = com.shifa.domain.PatientVisitFact.Status.COMMITTED
        ORDER BY COALESCE(f.committedAt, f.createdAt) DESC
        """
    )
    fun findCommittedTimeline(doctorId: Long, patientId: Long): List<PatientVisitFact>
}
