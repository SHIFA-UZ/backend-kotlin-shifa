package com.shifa.documents.application

import com.shifa.doctors.domain.DoctorProfile
import com.shifa.documents.domain.PatientDocument
import com.shifa.documents.infrastructure.DocumentAccessGrantRepository
import com.shifa.documents.infrastructure.PatientDocumentRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/** A document list asks for the doctor's grants once, and shows the same documents as the per-document check did. */
class PatientDocumentServiceGrantsTest {

    private val docs = mock(PatientDocumentRepository::class.java)
    private val grants = mock(DocumentAccessGrantRepository::class.java)
    private val storage = mock(PatientDocumentStorageService::class.java)
    private val service = PatientDocumentService(
        mock(com.shifa.patients.application.PatientProfiles::class.java),
        docs,
        storage,
        mock(com.shifa.doctors.application.DoctorProfiles::class.java),
        grants,
    )

    private fun doctor(id: Long) = mock(DoctorProfile::class.java).also {
        `when`(it.id).thenReturn(id)
        `when`(it.firstName).thenReturn("Doctor")
        `when`(it.lastName).thenReturn("$id")
    }

    @Test
    fun `list reads the grants in one query and keeps the visibility rules`() {
        val me = 7L
        val other = doctor(8)
        val mine = doctor(me)
        `when`(docs.listForPatient(1L)).thenReturn(
            listOf(
                PatientDocument(id = 10, filePath = "a.pdf", isSharedWithTeam = true),
                PatientDocument(id = 11, filePath = "b.pdf", uploadedByDoctor = mine),
                PatientDocument(id = 12, filePath = "c.pdf", uploadedByDoctor = other), // granted
                PatientDocument(id = 13, filePath = "d.pdf", uploadedByDoctor = other), // locked
            ),
        )
        `when`(grants.findGrantedDocumentIds(1L, me)).thenReturn(listOf(12L))
        `when`(storage.publicUrlFor(anyString())).thenReturn("url")

        val list = service.list(1L, me)

        assertEquals(listOf(true, true, true, false), list.map { it.canView })
        assertEquals(listOf("url", "url", "url", null), list.map { it.url })
        verify(grants, times(1)).findGrantedDocumentIds(1L, me)
        verify(grants, never()).existsByDocument_IdAndDoctor_Id(anyLong(), anyLong())
    }
}
