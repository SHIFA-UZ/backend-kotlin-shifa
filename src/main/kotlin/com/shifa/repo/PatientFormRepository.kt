package com.shifa.repo

import com.shifa.domain.PatientForm
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface PatientFormRepository : JpaRepository<PatientForm, Long> {

    fun findByPatientIdOrderByDateDesc(patientId: Long): List<PatientForm>

    fun findByPatientIdAndTemplateIdOrderByDateDesc(
        patientId: Long,
        templateId: String
    ): List<PatientForm>

    fun findByDocumentId(documentId: Long): PatientForm?

    @Query(
        """
        select max(pf.formNumber)
        from PatientForm pf
        where pf.patient.id = :patientId and pf.templateId = :templateId
        """
    )
    fun findMaxFormNumber(patientId: Long, templateId: String): Int?

    @org.springframework.data.jpa.repository.Query(
        """SELECT COUNT(pf) FROM PatientForm pf WHERE pf.createdByDoctor IS NOT NULL
           AND pf.createdByDoctor.id = :doctorId AND pf.createdAt >= :start AND pf.createdAt < :endExclusive"""
    )
    fun countByCreatedByDoctorInDateRange(
        @Param("doctorId") doctorId: Long,
        @Param("start") start: java.time.OffsetDateTime,
        @Param("endExclusive") endExclusive: java.time.OffsetDateTime,
    ): Long

    @Query(
        """SELECT COUNT(pf) FROM PatientForm pf WHERE pf.createdByDoctor IS NOT NULL AND pf.createdByDoctor.id = :doctorId"""
    )
    fun countByCreatedByDoctorAllTime(@Param("doctorId") doctorId: Long): Long

    @Query(
        """SELECT MAX(pf.createdAt) FROM PatientForm pf WHERE pf.createdByDoctor IS NOT NULL
           AND pf.createdByDoctor.id = :doctorId AND pf.createdAt >= :start AND pf.createdAt < :endExclusive"""
    )
    fun findMaxCreatedAtByDoctorInDateRange(
        @Param("doctorId") doctorId: Long,
        @Param("start") start: java.time.OffsetDateTime,
        @Param("endExclusive") endExclusive: java.time.OffsetDateTime,
    ): java.time.OffsetDateTime?

    @Query(
        """SELECT MAX(pf.createdAt) FROM PatientForm pf WHERE pf.createdByDoctor IS NOT NULL AND pf.createdByDoctor.id = :doctorId"""
    )
    fun findMaxCreatedAtByDoctor(@Param("doctorId") doctorId: Long): java.time.OffsetDateTime?
}