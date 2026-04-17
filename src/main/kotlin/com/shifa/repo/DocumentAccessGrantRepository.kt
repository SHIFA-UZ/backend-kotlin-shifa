package com.shifa.repo

import com.shifa.domain.DocumentAccessGrant
import org.springframework.data.jpa.repository.JpaRepository

interface DocumentAccessGrantRepository : JpaRepository<DocumentAccessGrant, Long> {

    fun existsByDocument_IdAndDoctor_Id(documentId: Long, doctorId: Long): Boolean
    fun findByDoctor_Id(doctorId: Long): List<DocumentAccessGrant>
    fun findByDocument_Patient_Id(patientId: Long): List<DocumentAccessGrant>
}
