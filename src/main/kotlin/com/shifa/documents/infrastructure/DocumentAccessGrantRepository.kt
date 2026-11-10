package com.shifa.documents.infrastructure

import com.shifa.documents.domain.DocumentAccessGrant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DocumentAccessGrantRepository : JpaRepository<DocumentAccessGrant, Long> {

    fun existsByDocument_IdAndDoctor_Id(documentId: Long, doctorId: Long): Boolean

    /** Ids of the patient's documents this doctor was granted: one query for a whole document list. */
    @Query("select g.document.id from DocumentAccessGrant g where g.doctor.id = :doctorId and g.document.patient.id = :patientId")
    fun findGrantedDocumentIds(@Param("patientId") patientId: Long, @Param("doctorId") doctorId: Long): List<Long>

    fun findByDoctor_Id(doctorId: Long): List<DocumentAccessGrant>
    fun findByDocument_Patient_Id(patientId: Long): List<DocumentAccessGrant>
}
