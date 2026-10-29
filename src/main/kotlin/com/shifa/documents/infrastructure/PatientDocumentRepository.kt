// src/main/kotlin/com/shifa/documents/infrastructure/PatientDocumentRepository.kt
package com.shifa.documents.infrastructure

import com.shifa.documents.domain.PatientDocument
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface PatientDocumentRepository : JpaRepository<PatientDocument, Long> {

    @Modifying
    @Query("update PatientDocument d set d.uploadedByDoctor = null where d.uploadedByDoctor.id = :doctorId")
    fun clearUploadedByDoctor(@Param("doctorId") doctorId: Long): Int

    @Query("select d from PatientDocument d where d.patient.id = :patientId order by d.date desc, d.id desc")
    fun listForPatient(patientId: Long): List<PatientDocument>  // <-- non-null Long

    @Query("select d from PatientDocument d where d.appointment.id = :appointmentId order by d.date desc, d.id desc")
    fun findByAppointmentId(@Param("appointmentId") appointmentId: Long): List<PatientDocument>

    /** (appointmentId, documentId) pairs; a projection, so no entity or lazy association is loaded. */
    @Query("select d.appointment.id, d.id from PatientDocument d where d.appointment.id in :appointmentIds")
    fun findIdPairsByAppointmentIds(@Param("appointmentIds") appointmentIds: Collection<Long>): List<Array<Any>>

    @Query(
        """
        SELECT d.appointment.id, COUNT(d.id) FROM PatientDocument d
        WHERE d.appointment.id IN :appointmentIds
        GROUP BY d.appointment.id
        """
    )
    fun countByAppointmentIds(
        @Param("appointmentIds") appointmentIds: Collection<Long>,
    ): List<Array<Any>>

    /**
     * Count documents uploaded by patients who had at least one appointment with this doctor
     * in the given appointment range, with document date in [docStart, docEnd].
     * Used for engagement analytics; no PII returned.
     */
    @Query(value = """
        SELECT COUNT(d.id) FROM patient_documents d
        WHERE d.patient_id IN (
            SELECT DISTINCT a.patient_id FROM appointments a
            WHERE a.doctor_id = :doctorId
              AND a.start_at >= :apptStart AND a.start_at < :apptEnd
        )
        AND d.date >= :docStart AND d.date < :docEnd
    """, nativeQuery = true)
    fun countDocumentsByDoctorPatientsInDateRange(
        @Param("doctorId") doctorId: Long,
        @Param("apptStart") apptStart: java.time.Instant,
        @Param("apptEnd") apptEnd: java.time.Instant,
        @Param("docStart") docStart: LocalDate,
        @Param("docEnd") docEnd: LocalDate
    ): Long

    @Query(
        """SELECT COUNT(d) FROM PatientDocument d WHERE d.uploadedByDoctor IS NOT NULL
           AND d.uploadedByDoctor.id = :doctorId AND d.date >= :from AND d.date <= :toInclusive"""
    )
    fun countByUploadedDoctorAndDocumentDateBetween(
        @Param("doctorId") doctorId: Long,
        @Param("from") from: LocalDate,
        @Param("toInclusive") toInclusive: LocalDate,
    ): Long

    @Query(
        """SELECT COUNT(d) FROM PatientDocument d WHERE d.uploadedByDoctor IS NOT NULL AND d.uploadedByDoctor.id = :doctorId"""
    )
    fun countUploadedByDoctorAllTime(@Param("doctorId") doctorId: Long): Long

    @Query("""
        SELECT d FROM PatientDocument d WHERE d.uploadedByDoctor IS NOT NULL AND d.uploadedByDoctor.id = :doctorId
        AND d.date >= :from AND d.date <= :toInclusive""")
    fun listUploadedByDoctorInDocumentDateRange(
        @Param("doctorId") doctorId: Long,
        @Param("from") from: LocalDate,
        @Param("toInclusive") toInclusive: LocalDate,
    ): List<PatientDocument>
}
