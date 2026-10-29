package com.shifa.documents.infrastructure

import com.shifa.documents.domain.DocumentAccessRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface DocumentAccessRequestRepository : JpaRepository<DocumentAccessRequest, Long> {

    fun existsByDocument_IdAndRequestingDoctor_IdAndStatus(
        documentId: Long,
        requestingDoctorId: Long,
        status: DocumentAccessRequest.Status
    ): Boolean

    fun findByDocument_IdAndRequestingDoctor_IdAndStatus(
        documentId: Long,
        requestingDoctorId: Long,
        status: DocumentAccessRequest.Status
    ): DocumentAccessRequest?

    /** The request with its document, the document's patient and the requesting doctor loaded: safe to read outside a transaction. */
    @Query(
        """
        select r from DocumentAccessRequest r
        join fetch r.document d
        left join fetch d.patient
        join fetch r.requestingDoctor
        where r.id = :id
        """,
    )
    fun findWithDetailsById(id: Long): DocumentAccessRequest?

    fun findByRequestingDoctor_Id(doctorId: Long): List<DocumentAccessRequest>
    fun findByDocument_Patient_Id(patientId: Long): List<DocumentAccessRequest>
}
