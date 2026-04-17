package com.shifa.repo

import com.shifa.domain.PatientForm
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
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
}